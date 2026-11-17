package com.example.dopaminecut2.service.runtime

import kotlinx.coroutines.*

data class ObservedSnapshot<O, T>(val owner: O, val epoch: Long, val revision: Long,
    val startedMs: Long, val completedMs: Long, val value: T)

/** Main-confined state; only read() runs on the dedicated reader dispatcher.
 * There is one native read in flight even when an account changes or a read is slow.
 */
class AsyncSnapshotCoordinator<O : Any, T : Any>(
    private val scope: CoroutineScope,
    private val readerDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val owner: () -> O?,
    private val read: suspend () -> T,
    private val consume: (ObservedSnapshot<O, T>) -> Unit,
    private val rejected: (String) -> Unit,
    private val usable: (T) -> Boolean = { true },
    private val intervalMs: Long = 150,
    private val maxReadMs: Long = 1_500
) {
    private data class Waiter<O, T>(val epoch: Long, val revision: Long,
        val result: CompletableDeferred<ObservedSnapshot<O, T>?>)
    private var runner: Job? = null
    private var epoch = 0L
    private var revision = 0L
    private var pending = false
    private var closed = false
    private var lastStarted: Long? = null
    private val waiters = mutableListOf<Waiter<O, T>>()
    private var latest: ObservedSnapshot<O, T>? = null

    fun cached(maxAgeMs: Long = 1_000, notBeforeMs: Long = Long.MIN_VALUE): ObservedSnapshot<O, T>? =
        latest?.takeIf { !closed && it.epoch == epoch && it.owner == owner() &&
            it.startedMs >= notBeforeMs && now() - it.startedMs in 0..maxAgeMs }

    fun request(): Long? {
        if (closed || owner() == null) return null
        pending = true
        val requested = ++revision
        if (runner?.isActive != true) {
            val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
            runner = job
            job.start()
        }
        return requested
    }

    suspend fun awaitFresh(maxAgeMs: Long = 300, notBeforeMs: Long = Long.MIN_VALUE): ObservedSnapshot<O, T>? {
        cached(maxAgeMs, notBeforeMs)?.let { return it }
        val startEpoch = epoch
        val waiter = Waiter<O, T>(startEpoch, revision + 1, CompletableDeferred())
        waiters += waiter
        return try {
            if (request() == null) return null
            waiter.result.await()
        } finally { waiters.remove(waiter) }
    }

    private suspend fun drain() {
        try {
            while (pending && !closed && currentCoroutineContext().isActive) {
                lastStarted?.let { delay((intervalMs - (now() - it)).coerceAtLeast(0)) }
                val readOwner = owner() ?: break
                val readEpoch = epoch
                val readRevision = revision
                pending = false
                val started = now()
                lastStarted = started
                val result = try {
                    Result.success(withContext(readerDispatcher) { read() })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                if (closed || readEpoch != epoch || readOwner != owner()) {
                    finishWaiters(readEpoch, readRevision, null)
                    continue
                }
                val value = result.getOrNull()
                if (value == null || now() - started > maxReadMs || !usable(value)) {
                    latest = null
                    rejected(if (result.isFailure) "READ_FAILED" else "READ_STALE_OR_INCOMPLETE")
                    finishWaiters(readEpoch, readRevision, null)
                    continue
                }
                val observed = ObservedSnapshot(readOwner, readEpoch, readRevision, started, now(), value)
                latest = observed
                consume(observed)
                // consume can invalidate the epoch (for example a user/account transition).
                finishWaiters(readEpoch, readRevision, observed.takeIf { epoch == readEpoch && owner() == readOwner })
            }
        } finally { runner = null }
    }

    private fun finishWaiters(epoch: Long, revision: Long, value: ObservedSnapshot<O, T>?) {
        waiters.filter { it.epoch == epoch && it.revision <= revision }.toList().forEach {
            waiters.remove(it)
            it.result.complete(value)
        }
    }

    fun invalidate() {
        epoch++
        latest = null
        pending = false
        waiters.toList().forEach { it.result.complete(null) }
        waiters.clear()
        // Do not cancel/join and replace an in-flight non-cooperative Android IPC read.
    }

    fun close() {
        closed = true
        invalidate()
        runner?.cancel()
    }
}
