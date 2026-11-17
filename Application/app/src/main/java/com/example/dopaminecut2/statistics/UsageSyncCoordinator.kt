package com.example.dopaminecut2.statistics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface UsageSyncSchedule {
    fun nextAt(uid: String): Long
    fun defer(uid: String, nextAt: Long)
    fun ensure(uid: String)
    fun cancel()
}

data class UsageSyncResult(val synced: Int = 0, val remaining: Int = 0,
    val deferred: Boolean = false, val failed: Boolean = false)

/** Daily cloud backup, independent of local tracking and intervention timers. */
class UsageSyncCoordinator(
    private val schedule: UsageSyncSchedule,
    private val currentUser: () -> String?,
    private val upload: suspend (String) -> RetrySummary,
    private val now: () -> Long = System::currentTimeMillis,
    private val onUploaded: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    suspend fun sync(uid: String, force: Boolean = false): UsageSyncResult = mutex.withLock {
        if (currentUser() != uid) return@withLock UsageSyncResult(deferred = true)
        schedule.ensure(uid)
        if (!force && now() < schedule.nextAt(uid)) return@withLock UsageSyncResult(deferred = true)
        try {
            val result = upload(uid)
            if (currentUser() != uid) return@withLock UsageSyncResult(deferred = true)
            schedule.defer(uid, now() + if (result.remaining == 0) DAY_MS else RETRY_MS)
            if (result.synced > 0) onUploaded(uid)
            UsageSyncResult(result.synced, result.remaining, failed = result.remaining > 0)
        } catch (cancelled: CancellationException) {
            schedule.defer(uid, now() + RETRY_MS)
            throw cancelled
        } catch (_: Exception) {
            schedule.defer(uid, now() + RETRY_MS)
            UsageSyncResult(failed = true)
        }
    }
    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val RETRY_MS = 30 * 60_000L
    }
}
