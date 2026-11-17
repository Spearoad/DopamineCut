package com.example.dopaminecut2.statistics

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

class UsageDrainException(message: String) : IllegalStateException(message)
data class UsageWriteHealth(val queued: Int, val failed: Int, val oldestWaitMs: Long)

/** FIFO receipts finish on every path. Failure blocks drain; a timeout never means records were saved.
 * Stage 2 deliberately retains sequential, in-memory admission: no dropping or unsafe delta replay/coalescing.
 */
class UsageWriteCoordinator(scope: CoroutineScope, private val now: () -> Long,
    private val execute: suspend (UsageWriteCommand) -> Unit,
    private val onFailure: (UsageWriteCommand, Throwable) -> Unit = { _, _ -> }) {
    private data class Entry(val id: Long, val command: UsageWriteCommand?, val acceptedAt: Long,
        val receipt: CompletableDeferred<Result<Unit>>)
    private val lock = Any()
    private val sequence = AtomicLong()
    private val queue = Channel<Entry>(Channel.UNLIMITED)
    private val pending = linkedMapOf<Long, Entry>()
    private val failed = mutableListOf<UsageWriteCommand>()
    private var closed = false
    val job: Job = scope.launch {
        for (entry in queue) {
            try {
                if (entry.command == null) {
                    val failures = synchronized(lock) { failed.size }
                    entry.receipt.complete(if (failures == 0) Result.success(Unit)
                        else Result.failure(UsageDrainException("$failures storage commands failed; drain is incomplete")))
                } else {
                    execute(entry.command)
                    entry.receipt.complete(Result.success(Unit))
                }
            } catch (cancelled: CancellationException) {
                entry.command?.let { synchronized(lock) { failed += it } }
                entry.receipt.complete(Result.failure(cancelled))
                throw cancelled
            } catch (error: Exception) {
                entry.command?.let { command ->
                    synchronized(lock) { failed += command }
                    // Diagnostics must not prevent receipts from completing.
                    entry.receipt.complete(Result.failure(error))
                    try { onFailure(command, error) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* health/receipt still records the failure */ }
                }
            } finally {
                synchronized(lock) { pending.remove(entry.id) }
            }
        }
    }.also { worker ->
        worker.invokeOnCompletion { cause ->
            synchronized(lock) {
                closed = true
                queue.close(cause)
                pending.values.forEach {
                    it.command?.let(failed::add)
                    it.receipt.complete(Result.failure(cause ?: UsageDrainException("Storage worker stopped before completing the command")))
                }
                pending.clear()
                while (queue.tryReceive().isSuccess) { /* commands are retained in failed, not duplicated in the channel */ }
            }
        }
    }

    fun submit(command: UsageWriteCommand, receipt: CompletableDeferred<Result<Unit>> = CompletableDeferred()): Deferred<Result<Unit>> = enqueue(command, receipt)
    private fun enqueue(command: UsageWriteCommand?, receipt: CompletableDeferred<Result<Unit>> = CompletableDeferred()): Deferred<Result<Unit>> = synchronized(lock) {
        if (closed || !job.isActive) {
            val error = UsageDrainException("Storage queue is closed")
            receipt.complete(Result.failure(error))
            if (command != null) {
                failed += command
                try { onFailure(command, error) } catch (_: Exception) { }
            }
        } else {
            val entry = Entry(sequence.incrementAndGet(), command, now(), receipt)
            pending[entry.id] = entry
            if (queue.trySend(entry).isFailure) {
                pending.remove(entry.id)
                val error = UsageDrainException("Storage command was not accepted")
                receipt.complete(Result.failure(error))
                if (command != null) {
                    failed += command
                    try { onFailure(command, error) } catch (_: Exception) { }
                }
            }
        }
        receipt
    }

    suspend fun drain(timeoutMs: Long = 15_000): Result<Unit> {
        val barrier = enqueue(null)
        return withTimeoutOrNull(timeoutMs) { barrier.await() }
            ?: Result.failure(UsageDrainException("Storage drain timed out; pending records are not discarded"))
    }
    fun close() { synchronized(lock) { closed = true; queue.close() } }
    fun health(): UsageWriteHealth = synchronized(lock) {
        UsageWriteHealth(pending.size, failed.size,
            pending.values.firstOrNull()?.let { (now() - it.acceptedAt).coerceAtLeast(0) } ?: 0)
    }
    fun failedCommands(): List<UsageWriteCommand> = synchronized(lock) { failed.toList() }
}
