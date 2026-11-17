package com.example.dopaminecut2.statistics

import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.CategoryUsage
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.domain.ContentCategory
import java.util.Calendar

/**
 * 한 사용자의 하루 사용량을 기기에 누적하는 절대값 스냅샷.
 * 같은 revision을 Firebase에 여러 번 전송해도 값이 중복되지 않는다.
 */
data class UsageSnapshot(
    val userId: String,
    val date: String,
    val revision: Long = 0L,
    val dirty: Boolean = false,
    val appUsage: Map<String, AppUsage> = emptyMap(),
    val categoryUsage: Map<String, CategoryUsage> = emptyMap(),
    val hourlyShortformCount: Map<String, Long> = emptyMap(),
    val deductedScore: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    // Local only. FirebaseDataSource serializes only aggregate fields explicitly.
    val shortformSessions: Map<String, ShortformSessionRecord> = emptyMap(),
    val baseRemoteRevision: Long = 0L,
    val baseRemoteDigest: String = ""
) {
    fun reconcile(statistics: DailyStatistics): UsageSnapshot =
        if (dirty || baseRemoteRevision > statistics.remoteRevision ||
            baseRemoteRevision == statistics.remoteRevision && baseRemoteRevision > 0) this
        else fromDaily(userId, statistics).copy(revision = revision, shortformSessions = shortformSessions)

    fun acknowledge(uploaded: UsageSnapshot): UsageSnapshot = if (baseRemoteRevision != uploaded.baseRemoteRevision) this
        else copy(baseRemoteRevision = uploaded.baseRemoteRevision + 1,
            baseRemoteDigest = UsageSyncIdentity.digest(UsageSyncIdentity.payload(uploaded)), dirty = revision != uploaded.revision)
    fun accumulate(event: UsageEvent): UsageSnapshot {
        require(event.userId == userId && event.date == date)
        val platformKey = event.platform.storageKey
        val currentPlatform = appUsage[platformKey] ?: AppUsage()
        val nextPlatforms = appUsage + (
            platformKey to currentPlatform.copy(
                runTimeSec = currentPlatform.runTimeSec + event.runTimeSec,
                shortformTimeSec = currentPlatform.shortformTimeSec + event.shortformTimeSec,
                shortformCount = currentPlatform.shortformCount + event.shortformCount
            )
        )

        val nextCategories = event.category?.let { category ->
            val current = categoryUsage[category.id] ?: CategoryUsage()
            categoryUsage + (
                category.id to current.copy(
                    count = current.count + event.shortformCount,
                    durationSec = current.durationSec + event.shortformTimeSec
                )
            )
        } ?: categoryUsage

        val nextHourly = if (event.shortformCount > 0L) {
            val calendar = Calendar.getInstance().apply { timeInMillis = event.createdAtEpochMs }
            val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
            hourlyShortformCount + (hour to (hourlyShortformCount[hour] ?: 0L) + event.shortformCount)
        } else {
            hourlyShortformCount
        }

        return copy(
            revision = revision + 1L,
            dirty = true,
            appUsage = nextPlatforms,
            categoryUsage = nextCategories,
            hourlyShortformCount = nextHourly,
            deductedScore = deductedScore + event.deductedScore,
            updatedAtEpochMs = event.createdAtEpochMs
        )
    }

    fun toDailyStatistics(): DailyStatistics = DailyStatistics(
        date = date,
        deductedScore = deductedScore,
        appUsage = appUsage,
        categoryUsage = categoryUsage,
        hourlyShortformCount = hourlyShortformCount,
        remoteRevision = baseRemoteRevision
    )

    /** Cumulative checkpoints may arrive twice or out of order; only new deltas are counted. */
    fun checkpointShortform(checkpoint: ShortformSessionCheckpoint): UsageSnapshot {
        require(checkpoint.userId == userId && checkpoint.date == date)
        val previous = shortformSessions[checkpoint.viewSessionId]
        if (previous != null) {
            require(previous.platform == checkpoint.platform) { "시청 세션의 플랫폼이 변경되었습니다." }
        }
        val duration = maxOf(previous?.durationSec ?: 0L, checkpoint.durationSec)
        val requestedCount = if (checkpoint.initialCategory == ContentCategory.LIVE ||
            previous?.category == ContentCategory.LIVE) 0L else checkpoint.count
        val count = maxOf(previous?.count ?: 0L, requestedCount)
        val durationDelta = duration - (previous?.durationSec ?: 0L)
        val countDelta = count - (previous?.count ?: 0L)
        if (previous != null && durationDelta == 0L && countDelta == 0L) return this

        val initialRecord = previous?.copy(durationSec = duration, count = count)
            ?: ShortformSessionRecord(
                platform = checkpoint.platform,
                durationSec = duration,
                count = count,
                occurredAtEpochMs = checkpoint.occurredAtEpochMs,
                category = checkpoint.initialCategory,
                classification = checkpoint.initialCategory.takeUnless { it == ContentCategory.UNKNOWN }
                    ?.let { UsageClassification(it, "CLASSIFIED", "RULE") }
            )
        val record = if (initialRecord.category == ContentCategory.LIVE) initialRecord.copy(
            classification = UsageClassification(
                ContentCategory.LIVE, "CLASSIFIED", "RULE", deductedScore = count.coerceAtMost(50_000L)
            )
        ) else initialRecord
        val scoreDelta = (record.classification?.deductedScore ?: 0L) -
            (previous?.classification?.deductedScore ?: 0L)
        return accumulate(
            UsageEvent(
                userId = userId,
                date = date,
                platform = checkpoint.platform,
                shortformTimeSec = durationDelta,
                shortformCount = countDelta,
                category = record.category,
                deductedScore = scoreDelta,
                createdAtEpochMs = if (record.category == ContentCategory.LIVE) {
                    checkpoint.occurredAtEpochMs
                } else record.occurredAtEpochMs
            )
        ).copy(
            shortformSessions = shortformSessions + (checkpoint.viewSessionId to record),
            updatedAtEpochMs = maxOf(updatedAtEpochMs, checkpoint.occurredAtEpochMs)
        )
    }

    /** A terminal result patches category totals only; it never repeats platform/hour usage. */
    fun resolveShortformCategory(
        viewSessionId: String,
        classification: UsageClassification,
        resolvedAtEpochMs: Long
    ): UsageSnapshot {
        val record = requireNotNull(shortformSessions[viewSessionId]) {
            "먼저 시청 세션을 기기에 저장해야 합니다."
        }
        if (!record.isPending) return this
        val nextCategories = categoryUsage.toMutableMap()
        if (record.category != classification.category) {
            val original = nextCategories[record.category.id] ?: CategoryUsage()
            require(original.count >= record.count && original.durationSec >= record.durationSec)
            nextCategories[record.category.id] = original.copy(
                count = original.count - record.count,
                durationSec = original.durationSec - record.durationSec
            )
            val destination = nextCategories[classification.category.id] ?: CategoryUsage()
            nextCategories[classification.category.id] = destination.copy(
                count = destination.count + record.count,
                durationSec = destination.durationSec + record.durationSec
            )
        }
        val aggregatesChanged = record.category != classification.category || classification.deductedScore > 0L
        return copy(
            revision = revision + if (aggregatesChanged) 1L else 0L,
            dirty = dirty || aggregatesChanged,
            categoryUsage = nextCategories,
            deductedScore = deductedScore + classification.deductedScore,
            updatedAtEpochMs = if (aggregatesChanged) maxOf(updatedAtEpochMs, resolvedAtEpochMs) else updatedAtEpochMs,
            shortformSessions = shortformSessions + (
                viewSessionId to record.copy(category = classification.category, classification = classification)
            )
        )
    }

    /** Transient OCR was lost on restart. Keep all usage, with an explicit unknown outcome. */
    fun finalizePendingClassifications(reason: String): UsageSnapshot = copy(
        shortformSessions = shortformSessions.mapValues { (_, record) ->
            if (record.isPending) record.copy(
                classification = UsageClassification(ContentCategory.UNKNOWN, "UNRESOLVED", "LOCAL", reason)
            ) else record
        }
    )

    companion object {
        fun empty(userId: String, date: String): UsageSnapshot =
            UsageSnapshot(userId = userId, date = date)

        fun fromDaily(userId: String, statistics: DailyStatistics): UsageSnapshot = UsageSnapshot(
            userId = userId,
            date = statistics.date,
            appUsage = statistics.appUsage,
            categoryUsage = statistics.categoryUsage,
            hourlyShortformCount = statistics.hourlyShortformCount,
            deductedScore = statistics.deductedScore,
            baseRemoteRevision = statistics.remoteRevision
        ).let { it.copy(baseRemoteDigest = UsageSyncIdentity.digest(UsageSyncIdentity.payload(it))) }
    }
}

interface UsageSnapshotStore {
    suspend fun accumulate(event: UsageEvent): UsageSnapshot
    suspend fun checkpointShortform(checkpoint: ShortformSessionCheckpoint): UsageSnapshot
    suspend fun resolveShortformCategory(
        userId: String,
        date: String,
        viewSessionId: String,
        classification: UsageClassification,
        resolvedAtEpochMs: Long
    ): UsageSnapshot
    suspend fun finalizePendingClassifications(userId: String, reason: String)
    suspend fun get(userId: String, date: String): UsageSnapshot?
    suspend fun getRange(userId: String, startDate: String, endDate: String): List<UsageSnapshot>
    suspend fun seedIfAbsent(snapshot: UsageSnapshot)
    suspend fun pending(userId: String, limit: Int): List<UsageSnapshot>
    suspend fun markSynced(userId: String, date: String, revision: Long)
    suspend fun acknowledgeRemote(snapshot: UsageSnapshot) = markSynced(snapshot.userId, snapshot.date, snapshot.revision)
    suspend fun reconcileRemote(userId: String, statistics: DailyStatistics): UsageSnapshot {
        seedIfAbsent(UsageSnapshot.fromDaily(userId, statistics))
        return get(userId, statistics.date)?.reconcile(statistics) ?: UsageSnapshot.fromDaily(userId, statistics)
    }
}
