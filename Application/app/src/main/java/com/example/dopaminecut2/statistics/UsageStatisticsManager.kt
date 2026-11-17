package com.example.dopaminecut2.statistics

import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.remote.RemoteUserDataSource
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.time.WallClock

enum class RecordStatus {
    BUFFERED,
    SYNCED
}

data class RetrySummary(
    val attempted: Int,
    val synced: Int,
    val remaining: Int
)

/** 사용 이벤트는 로컬 절대값에 합치고, Firebase에는 여러 이벤트를 한 번에 동기화한다. */
class UsageStatisticsManager(
    private val remoteDataSource: RemoteUserDataSource,
    private val snapshotStore: UsageSnapshotStore,
    private val wallClock: WallClock
) {
    suspend fun prepareSession(
        userId: String,
        date: String,
        remoteStatistics: DailyStatistics?
    ) {
        snapshotStore.seedIfAbsent(
            remoteStatistics?.let { UsageSnapshot.fromDaily(userId, it) }
                ?: UsageSnapshot.empty(userId, date)
        )
    }

    suspend fun recordRunTime(
        userId: String,
        date: String,
        platform: SupportedPlatform,
        runTimeSec: Long
    ): Result<RecordStatus> {
        if (runTimeSec <= 0L) return Result.success(RecordStatus.SYNCED)
        return record(
            UsageEvent(
                userId = userId,
                date = date,
                platform = platform,
                runTimeSec = runTimeSec,
                createdAtEpochMs = wallClock.now().time
            )
        )
    }

    suspend fun recordShortformView(
        userId: String,
        date: String,
        platform: SupportedPlatform,
        durationSec: Long,
        category: ContentCategory,
        deductedScore: Long
    ): Result<RecordStatus> {
        return recordShortformSession(
            userId = userId,
            date = date,
            platform = platform,
            durationSec = durationSec,
            count = 1L,
            category = category,
            deductedScore = deductedScore
        )
    }

    suspend fun recordShortformSession(
        userId: String,
        date: String,
        platform: SupportedPlatform,
        durationSec: Long,
        count: Long,
        category: ContentCategory,
        deductedScore: Long
    ): Result<RecordStatus> {
        if (durationSec < MIN_SHORTFORM_DURATION_SEC) {
            return Result.failure(IllegalArgumentException("숏폼 기록은 5초 이상이어야 합니다."))
        }
        if (count < 0L) {
            return Result.failure(IllegalArgumentException("숏폼 횟수는 0 이상이어야 합니다."))
        }
        return record(
            UsageEvent(
                userId = userId,
                date = date,
                platform = platform,
                shortformTimeSec = durationSec,
                shortformCount = if (category == ContentCategory.LIVE) 0L else count,
                category = category,
                deductedScore = if (category == ContentCategory.LIVE) 0L else deductedScore,
                createdAtEpochMs = wallClock.now().time
            )
        )
    }

    suspend fun retryPending(userId: String, limit: Int = 31): RetrySummary {
        val pending = snapshotStore.pending(userId, limit)
        if (pending.isEmpty()) return RetrySummary(0, 0, 0)

        var synced = 0
        try {
            remoteDataSource.syncUsageSnapshots(pending).forEach { snapshot ->
                snapshotStore.acknowledgeRemote(snapshot)
                synced++
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A conflict/failure must not discard or acknowledge this local revision.
        }
        return RetrySummary(
            attempted = pending.size,
            synced = synced,
            // `remaining` is a count, not a boolean "has more" flag. Asking for
            // one item made every value above one look like `1` to callers.
            remaining = snapshotStore.pending(userId, Int.MAX_VALUE).size
        )
    }

    suspend fun checkpointShortformSession(
        userId: String,
        date: String,
        viewSessionId: String,
        platform: SupportedPlatform,
        durationSec: Long,
        count: Long,
        occurredAtEpochMs: Long,
        initialCategory: ContentCategory = ContentCategory.UNKNOWN
    ): Result<RecordStatus> = runCatching {
        snapshotStore.checkpointShortform(
            ShortformSessionCheckpoint(
                userId, date, viewSessionId, platform, durationSec, count, occurredAtEpochMs, initialCategory
            )
        )
        RecordStatus.BUFFERED
    }

    suspend fun resolveShortformCategory(
        userId: String,
        date: String,
        viewSessionId: String,
        classification: UsageClassification
    ): Result<RecordStatus> = runCatching {
        snapshotStore.resolveShortformCategory(userId, date, viewSessionId, classification, wallClock.now().time)
        RecordStatus.BUFFERED
    }

    suspend fun finalizePendingClassifications(userId: String, reason: String): Result<Unit> = runCatching {
        snapshotStore.finalizePendingClassifications(userId, reason)
    }

    private suspend fun record(event: UsageEvent): Result<RecordStatus> = runCatching {
        snapshotStore.accumulate(event)
        RecordStatus.BUFFERED
    }

    private companion object {
        const val MIN_SHORTFORM_DURATION_SEC = 5L
    }
}
