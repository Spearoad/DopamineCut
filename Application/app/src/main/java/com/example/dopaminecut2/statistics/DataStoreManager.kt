package com.example.dopaminecut2.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.CategoryUsage
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.statistics.ShortformSessionCheckpoint
import com.example.dopaminecut2.statistics.ShortformSessionRecord
import com.example.dopaminecut2.statistics.UsageClassification
import com.example.dopaminecut2.statistics.UsageEvent
import com.example.dopaminecut2.statistics.UsageSnapshot
import com.example.dopaminecut2.statistics.UsageSnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Context.dataStore by preferencesDataStore(name = "dopamine_settings")

enum class OnboardingStage {
    INTERVENTION,
    MEASUREMENT,
    COMPLETE
}

data class OnboardingProgress(
    val stage: OnboardingStage = OnboardingStage.INTERVENTION,
    val answers: Map<String, Set<String>> = emptyMap()
)

interface OnboardingStore {
    fun observeOnboarding(userId: String): Flow<OnboardingProgress>
    suspend fun setOnboardingStage(userId: String, stage: OnboardingStage)
    suspend fun saveInterventionChoice(userId: String, mode: com.example.dopaminecut2.domain.InterventionMode)
    suspend fun finishMeasurementSetup(userId: String, deferred: Boolean, nowEpochMs: Long)
}

class DataStoreManager(private val context: Context) : OnboardingStore, UsageSnapshotStore, NotificationSettingsStore, GoalSyncLocalStore, InterventionLedgerStore, PendingRecognitionStore {
    private fun recognitionKey(uid: String) = stringPreferencesKey("pending_recognition_$uid")

    private fun decodeRecognitions(uid: String, raw: String?): List<PendingRecognition> {
        if (raw == null) return emptyList()
        val data = org.json.JSONArray(raw)
        return (0 until data.length()).map { i ->
            val row = data.getJSONObject(i)
            PendingRecognition(uid, row.getString("date"), row.getString("sessionId"),
                SupportedPlatform.entries.single { it.storageKey == row.getString("platform") },
                row.getLong("durationSec"), row.getBoolean("ended"), row.getLong("updatedAtEpochMs"))
        }
    }

    override suspend fun pendingRecognitions(userId: String): List<PendingRecognition> {
        require(userId.isNotBlank())
        return decodeRecognitions(userId, context.dataStore.data.first()[recognitionKey(userId)])
    }

    override suspend fun upsertPendingRecognition(record: PendingRecognition) {
        context.dataStore.edit { preferences ->
            val key = recognitionKey(record.userId)
            val retained = (decodeRecognitions(record.userId, preferences[key]).filter { it.sessionId != record.sessionId } + record)
                .filter { it.updatedAtEpochMs >= record.updatedAtEpochMs - 30L * 86_400_000L }
                .sortedByDescending { it.updatedAtEpochMs }.take(500)
            preferences[key] = org.json.JSONArray(retained.map { row -> JSONObject()
                .put("date", row.date).put("sessionId", row.sessionId).put("platform", row.platform.storageKey)
                .put("durationSec", row.durationSec).put("ended", row.ended).put("updatedAtEpochMs", row.updatedAtEpochMs)
            }).toString()
        }
    }

    override suspend fun removePendingRecognition(userId: String, sessionId: String) {
        require(userId.isNotBlank())
        context.dataStore.edit { preferences ->
            val key = recognitionKey(userId)
            val data = org.json.JSONArray(preferences[key] ?: "[]")
            val kept = org.json.JSONArray()
            for (i in 0 until data.length()) {
                val row = data.getJSONObject(i)
                if (row.getString("sessionId") != sessionId) kept.put(row)
            }
            if (kept.length() == 0) preferences.remove(key) else preferences[key] = kept.toString()
        }
    }

    private val usageSnapshotsKey = stringSetPreferencesKey("usage_snapshots_v2")
    suspend fun deleteUsage(uid: String, date: String?) {
        context.dataStore.edit { prefs ->
            prefs[usageSnapshotsKey] = decodeSnapshots(prefs[usageSnapshotsKey])
                .filterNot { it.userId == uid && (date == null || it.date == date) }.map(::encodeSnapshot).toSet()
            if (date == null) prefs.remove(recognitionKey(uid)) else {
                val pending = decodeRecognitions(uid, prefs[recognitionKey(uid)]).filterNot { it.date == date }
                prefs[recognitionKey(uid)] = org.json.JSONArray(pending.map { row -> JSONObject()
                    .put("date", row.date).put("sessionId", row.sessionId).put("platform", row.platform.storageKey)
                    .put("durationSec", row.durationSec).put("ended", row.ended).put("updatedAtEpochMs", row.updatedAtEpochMs)
                }).toString()
            }
        }
    }
    override suspend fun loadInterventionLedger(uid: String, date: String): InterventionLedger {
        require(uid.isNotBlank() && date.matches(Regex("[0-9]{8}")))
        val raw = context.dataStore.data.first()[stringPreferencesKey("intervention_$uid")] ?: return InterventionLedger()
        val data = JSONObject(raw)
        if (data.getString("date") != date) return InterventionLedger()
        val notified = data.getJSONArray("notified")
        val snoozes = data.getJSONObject("snoozes")
        return InterventionLedger((0 until notified.length()).map { notified.getString(it) }.toSet(),
            snoozes.keys().asSequence().associateWith { snoozes.getLong(it) })
    }
    override suspend fun saveInterventionLedger(uid: String, date: String, ledger: InterventionLedger) {
        require(uid.isNotBlank() && date.matches(Regex("[0-9]{8}")))
        val data = JSONObject().put("date", date)
            .put("notified", org.json.JSONArray(ledger.notified.toList()))
            .put("snoozes", JSONObject(ledger.snoozedUntil))
        context.dataStore.edit { it[stringPreferencesKey("intervention_$uid")] = data.toString() }
    }

    override suspend fun syncSnapshot(userId: String): GoalSyncSnapshot {
        require(userId.isNotBlank())
        val preferences = context.dataStore.data.first()
        val raw = preferences[stringPreferencesKey("managed_goals_$userId")]
        return GoalSyncSnapshot(GoalStorageCodec.decode(raw), preferences[longPreferencesKey("goals_cloud_version_$userId")] ?: 0L,
            raw, raw != preferences[stringPreferencesKey("goals_ack_$userId")])
    }

    override suspend fun acknowledgeGoals(userId: String, captured: GoalSyncSnapshot, cloudVersion: Long) {
        require(userId.isNotBlank() && cloudVersion >= 0)
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("goals_cloud_version_$userId")] = cloudVersion
            preferences[stringPreferencesKey("goals_ack_$userId")] = captured.encoded ?: GoalStorageCodec.encode(captured.goals)
        }
    }

    override suspend fun importGoals(userId: String, captured: GoalSyncSnapshot, goals: List<com.example.dopaminecut2.domain.ManagedGoal>, cloudVersion: Long): Boolean {
        require(userId.isNotBlank() && cloudVersion >= 0)
        val raw = GoalStorageCodec.encode(com.example.dopaminecut2.domain.GoalCollection.importRemote(captured.goals, goals))
        var imported = false
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("managed_goals_$userId")
            if (preferences[key] != captured.encoded) return@edit
            preferences[key] = raw
            preferences[stringPreferencesKey("goals_ack_$userId")] = raw
            preferences[longPreferencesKey("goals_cloud_version_$userId")] = cloudVersion
            val onboardingKey = stringPreferencesKey("onboarding_$userId")
            val progress = decodeOnboarding(preferences[onboardingKey])
            preferences[onboardingKey] = encodeOnboarding(progress.copy(answers = progress.answers +
                    (OnboardingFlowPolicy.GOAL_MODE_KEY to setOf(OnboardingFlowPolicy.MANAGED_GOALS))))
            imported = true
        }
        return imported
    }

    override fun observeGoals(userId: String): Flow<List<com.example.dopaminecut2.domain.ManagedGoal>> {
        require(userId.isNotBlank())
        val key = stringPreferencesKey("managed_goals_$userId")
        return context.dataStore.data.map { GoalStorageCodec.decode(it[key]) }.distinctUntilChanged()
    }

    override suspend fun saveGoals(userId: String, goals: List<com.example.dopaminecut2.domain.ManagedGoal>) {
        require(userId.isNotBlank())
        val key = stringPreferencesKey("managed_goals_$userId")
        val onboardingKey = stringPreferencesKey("onboarding_$userId")
        context.dataStore.edit { preferences ->
            preferences[key] = GoalStorageCodec.encode(com.example.dopaminecut2.domain.GoalCollection.save(GoalStorageCodec.decode(preferences[key]), goals))
            val progress = decodeOnboarding(preferences[onboardingKey])
            // An explicitly saved managed goal replaces legacy limits, never enables signup defaults.
            preferences[onboardingKey] = encodeOnboarding(progress.copy(answers = progress.answers +
                    (OnboardingFlowPolicy.GOAL_MODE_KEY to setOf(OnboardingFlowPolicy.MANAGED_GOALS))))
        }
    }

    override suspend fun setGoalStatus(userId: String, metric: com.example.dopaminecut2.domain.GoalMetric, revision: Long, status: com.example.dopaminecut2.domain.GoalStatus) {
        require(userId.isNotBlank())
        val key = stringPreferencesKey("managed_goals_$userId")
        context.dataStore.edit { preferences ->
            preferences[key] = GoalStorageCodec.encode(com.example.dopaminecut2.domain.GoalCollection.setStatus(GoalStorageCodec.decode(preferences[key]), metric, revision, status))
        }
    }

    override suspend fun saveInterventionChoice(userId: String, mode: com.example.dopaminecut2.domain.InterventionMode) {
        require(userId.isNotBlank())
        val key = stringPreferencesKey("onboarding_$userId")
        context.dataStore.edit { preferences ->
            preferences[key] = encodeOnboarding(OnboardingFlowPolicy.withIntervention(decodeOnboarding(preferences[key]), mode))
        }
    }

    override suspend fun finishMeasurementSetup(userId: String, deferred: Boolean, nowEpochMs: Long) {
        require(userId.isNotBlank())
        val key = stringPreferencesKey("onboarding_$userId")
        context.dataStore.edit { preferences ->
            preferences[key] = encodeOnboarding(
                OnboardingFlowPolicy.finishMeasurement(decodeOnboarding(preferences[key]), deferred, nowEpochMs)
            )
        }
    }

    override fun observeNotificationSettings(userId: String): Flow<NotificationSettings> {
        require(userId.isNotBlank())
        val key = stringSetPreferencesKey("notification_options_$userId")
        return context.dataStore.data.map { NotificationSettings.fromStorageIds(it[key]) }
    }

    override suspend fun saveNotificationSettings(userId: String, settings: NotificationSettings) {
        require(userId.isNotBlank())
        val key = stringSetPreferencesKey("notification_options_$userId")
        context.dataStore.edit { it[key] = settings.storageIds() }
    }

    override fun observeOnboarding(userId: String): Flow<OnboardingProgress> {
        val key = stringPreferencesKey("onboarding_$userId")
        return context.dataStore.data.map { preferences -> decodeOnboarding(preferences[key]) }
    }

    override suspend fun setOnboardingStage(userId: String, stage: OnboardingStage) {
        val key = stringPreferencesKey("onboarding_$userId")
        context.dataStore.edit { preferences ->
            val current = decodeOnboarding(preferences[key])
            preferences[key] = encodeOnboarding(current.copy(stage = stage))
        }
    }

    override suspend fun accumulate(event: UsageEvent): UsageSnapshot {
        var result = UsageSnapshot.empty(event.userId, event.date)
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            val index = snapshots.indexOfFirst { it.userId == event.userId && it.date == event.date }
            val current = snapshots.getOrNull(index) ?: UsageSnapshot.empty(event.userId, event.date)
            result = current.accumulate(event)
            if (index >= 0) snapshots[index] = result else snapshots += result
            preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
        }
        return result
    }

    override suspend fun get(userId: String, date: String): UsageSnapshot? =
        decodeSnapshots(context.dataStore.data.first()[usageSnapshotsKey])
            .firstOrNull { it.userId == userId && it.date == date }

    override suspend fun checkpointShortform(checkpoint: ShortformSessionCheckpoint): UsageSnapshot {
        // Reject expired callbacks instead of re-counting a session whose ledger was pruned.
        require(checkpoint.date >= sessionRetentionStart()) { "시청 세션의 로컬 중복 검사 보관 기간이 지났습니다." }
        var result: UsageSnapshot? = null
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            require(snapshots.none {
                checkpoint.viewSessionId in it.shortformSessions &&
                        (it.userId != checkpoint.userId || it.date != checkpoint.date)
            }) { "다른 계정 또는 날짜의 시청 세션입니다." }
            val index = snapshots.indexOfFirst { it.userId == checkpoint.userId && it.date == checkpoint.date }
            val current = snapshots.getOrNull(index) ?: UsageSnapshot.empty(checkpoint.userId, checkpoint.date)
            val next = current.checkpointShortform(checkpoint)
            result = next
            if (current != next || index < 0) {
                if (index >= 0) snapshots[index] = next else snapshots += next
                preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
            }
        }
        return checkNotNull(result)
    }

    override suspend fun resolveShortformCategory(
        userId: String,
        date: String,
        viewSessionId: String,
        classification: UsageClassification,
        resolvedAtEpochMs: Long
    ): UsageSnapshot {
        var result: UsageSnapshot? = null
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            val index = snapshots.indexOfFirst { it.userId == userId && it.date == date }
            val current = requireNotNull(snapshots.getOrNull(index)) { "시청 기록을 먼저 저장해야 합니다." }
            val next = current.resolveShortformCategory(viewSessionId, classification, resolvedAtEpochMs)
            result = next
            if (next != current) {
                snapshots[index] = next
                preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
            }
        }
        return checkNotNull(result)
    }

    override suspend fun finalizePendingClassifications(userId: String, reason: String) {
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey])
            val updated = snapshots.map {
                if (it.userId == userId) it.finalizePendingClassifications(reason) else it
            }
            if (updated != snapshots) {
                preferences[usageSnapshotsKey] = prune(updated).map(::encodeSnapshot).toSet()
            }
        }
    }

    override suspend fun getRange(
        userId: String,
        startDate: String,
        endDate: String
    ): List<UsageSnapshot> = decodeSnapshots(context.dataStore.data.first()[usageSnapshotsKey])
        .filter { it.userId == userId && it.date in startDate..endDate }
        .sortedBy(UsageSnapshot::date)

    override suspend fun seedIfAbsent(snapshot: UsageSnapshot) {
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            if (snapshots.none { it.userId == snapshot.userId && it.date == snapshot.date }) {
                snapshots += snapshot.copy(dirty = false)
                preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
            }
        }
    }

    override suspend fun pending(userId: String, limit: Int): List<UsageSnapshot> =
        decodeSnapshots(context.dataStore.data.first()[usageSnapshotsKey])
            .filter { it.userId == userId && it.dirty }
            .sortedBy(UsageSnapshot::date)
            .take(limit.coerceAtLeast(0))

    override suspend fun markSynced(userId: String, date: String, revision: Long) {
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            val index = snapshots.indexOfFirst { it.userId == userId && it.date == date }
            val current = snapshots.getOrNull(index)
            if (current != null && current.revision == revision) {
                snapshots[index] = current.copy(dirty = false)
                preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
            }
        }
    }

    override suspend fun acknowledgeRemote(snapshot: UsageSnapshot) {
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            val index = snapshots.indexOfFirst { it.userId == snapshot.userId && it.date == snapshot.date }
            val current = snapshots.getOrNull(index) ?: return@edit
            if (current.baseRemoteRevision == snapshot.baseRemoteRevision) {
                snapshots[index] = current.acknowledge(snapshot)
                preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
            }
        }
    }

    override suspend fun reconcileRemote(userId: String, statistics: com.example.dopaminecut2.data.model.DailyStatistics): UsageSnapshot {
        var result = UsageSnapshot.fromDaily(userId, statistics)
        context.dataStore.edit { preferences ->
            val snapshots = decodeSnapshots(preferences[usageSnapshotsKey]).toMutableList()
            val index = snapshots.indexOfFirst { it.userId == userId && it.date == statistics.date }
            val current = snapshots.getOrNull(index)
            result = current?.reconcile(statistics) ?: result
            if (index < 0) snapshots.add(result) else snapshots[index] = result
            preferences[usageSnapshotsKey] = prune(snapshots).map(::encodeSnapshot).toSet()
        }
        return result
    }

    private fun prune(values: List<UsageSnapshot>): List<UsageSnapshot> = values
        .groupBy(UsageSnapshot::userId)
        .flatMap { (_, userValues) ->
            val dirty = userValues.filter(UsageSnapshot::dirty)
            val clean = userValues.filterNot(UsageSnapshot::dirty)
                .sortedByDescending(UsageSnapshot::date)
                .take(LOCAL_RETENTION_DAYS)
            (dirty + clean).distinctBy(UsageSnapshot::date).map { snapshot ->
                if (snapshot.date < sessionRetentionStart() && snapshot.shortformSessions.isNotEmpty()) {
                    snapshot.copy(shortformSessions = emptyMap())
                } else snapshot
            }
        }

    private fun sessionRetentionStart(): String = LocalDate.now()
        .minusDays(SESSION_RETENTION_DAYS - 1L)
        .format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun encodeSnapshot(value: UsageSnapshot): String = JSONObject().apply {
        put("uid", value.userId)
        put("date", value.date)
        put("revision", value.revision)
        put("remoteRevision", value.baseRemoteRevision)
        put("remoteDigest", value.baseRemoteDigest)
        put("dirty", value.dirty)
        put("deducted", value.deductedScore)
        put("updated", value.updatedAtEpochMs)
        put("apps", JSONObject().apply {
            value.appUsage.forEach { (key, usage) ->
                put(key, JSONObject().apply {
                    put("app", usage.runTimeSec)
                    put("short", usage.shortformTimeSec)
                    put("count", usage.shortformCount)
                    put("hourlyTime", JSONObject(usage.hourlyShortformTimeSec))
                    put("hourlyCount", JSONObject(usage.hourlyShortformCount))
                })
            }
        })
        put("categories", JSONObject().apply {
            value.categoryUsage.forEach { (key, usage) ->
                put(key, JSONObject().apply {
                    put("count", usage.count)
                    put("duration", usage.durationSec)
                })
            }
        })
        put("hours", JSONObject(value.hourlyShortformCount))
        put("sessions", JSONObject().apply {
            value.shortformSessions.forEach { (sessionId, record) ->
                put(sessionId, JSONObject().apply {
                    put("platform", record.platform.storageKey)
                    put("duration", record.durationSec)
                    put("count", record.count)
                    put("occurred", record.occurredAtEpochMs)
                    put("category", record.category.id)
                    record.classification?.let { classification ->
                        put("classification", JSONObject().apply {
                            put("category", classification.category.id)
                            put("status", classification.status)
                            put("source", classification.source)
                            put("reason", classification.reason)
                            put("model", classification.modelVersion)
                            put("taxonomy", classification.taxonomyVersion)
                            put("confidence", classification.confidence)
                            put("deducted", classification.deductedScore)
                        })
                    }
                })
            }
        })
    }.toString()

    private fun decodeSnapshots(values: Set<String>?): List<UsageSnapshot> =
        values.orEmpty().mapNotNull(::decodeSnapshot)

    private fun decodeSnapshot(encoded: String): UsageSnapshot? = runCatching {
        val json = JSONObject(encoded)
        UsageSnapshot(
            userId = json.getString("uid"),
            date = json.getString("date"),
            revision = json.optLong("revision"),
            baseRemoteRevision = json.optLong("remoteRevision"),
            baseRemoteDigest = json.optString("remoteDigest"),
            dirty = json.optBoolean("dirty"),
            appUsage = json.optJSONObject("apps").toAppUsageMap(),
            categoryUsage = json.optJSONObject("categories").toCategoryUsageMap(),
            hourlyShortformCount = json.optJSONObject("hours").toLongMap(),
            deductedScore = json.optLong("deducted"),
            updatedAtEpochMs = json.optLong("updated"),
            shortformSessions = json.optJSONObject("sessions").toShortformSessions()
        )
    }.getOrNull()

    private fun JSONObject?.toAppUsageMap(): Map<String, AppUsage> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key ->
            getJSONObject(key).let { value ->
                AppUsage(
                    runTimeSec = value.optLong("app"),
                    shortformTimeSec = value.optLong("short"),
                    shortformCount = value.optLong("count"),
                    hourlyShortformTimeSec = value.optJSONObject("hourlyTime").toLongMap(),
                    hourlyShortformCount = value.optJSONObject("hourlyCount").toLongMap()
                )
            }
        }
    }

    private fun JSONObject?.toCategoryUsageMap(): Map<String, CategoryUsage> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key ->
            getJSONObject(key).let { value ->
                CategoryUsage(value.optLong("count"), value.optLong("duration"))
            }
        }
    }

    private fun JSONObject?.toLongMap(): Map<String, Long> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith(::optLong)
    }

    private fun JSONObject?.toShortformSessions(): Map<String, ShortformSessionRecord> {
        if (this == null) return emptyMap() // Snapshots from the previous app have no ledger.
        return keys().asSequence().associateWith { key ->
            val value = getJSONObject(key)
            ShortformSessionRecord(
                platform = requireNotNull(SupportedPlatform.fromStorageKey(value.getString("platform"))),
                durationSec = value.getLong("duration"),
                count = value.getLong("count"),
                occurredAtEpochMs = value.getLong("occurred"),
                category = ContentCategory.fromStored(value.getString("category")),
                classification = value.optJSONObject("classification")?.let { classification ->
                    UsageClassification(
                        category = ContentCategory.fromStored(classification.getString("category")),
                        status = classification.getString("status"),
                        source = classification.getString("source"),
                        reason = classification.optNullableString("reason"),
                        modelVersion = classification.optNullableString("model"),
                        taxonomyVersion = classification.optInt("taxonomy", 1),
                        confidence = if (classification.has("confidence") && !classification.isNull("confidence")) {
                            classification.getDouble("confidence")
                        } else null,
                        deductedScore = classification.optLong("deducted")
                    )
                }
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun encodeOnboarding(progress: OnboardingProgress): String = buildString {
        append(progress.stage.name)
        progress.answers.toSortedMap().forEach { (questionId, answers) ->
            append(ONBOARDING_SECTION_SEPARATOR)
            append(questionId)
            append(ONBOARDING_VALUE_SEPARATOR)
            append(answers.sorted().joinToString(ONBOARDING_ANSWER_SEPARATOR))
        }
    }

    private fun decodeOnboarding(encoded: String?): OnboardingProgress {
        if (encoded.isNullOrBlank()) return OnboardingProgress()
        val sections = encoded.split(ONBOARDING_SECTION_SEPARATOR)
        val stage = runCatching { OnboardingStage.valueOf(sections.first()) }
            .getOrDefault(OnboardingStage.INTERVENTION)
        val answers = sections.drop(1).mapNotNull { section ->
            val separator = section.indexOf(ONBOARDING_VALUE_SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            val questionId = section.substring(0, separator)
            val values = section.substring(separator + 1)
                .split(ONBOARDING_ANSWER_SEPARATOR)
                .filter(String::isNotBlank)
                .toSet()
            questionId to values
        }.toMap()
        return OnboardingProgress(stage = stage, answers = answers)
    }

    private companion object {
        const val LOCAL_RETENTION_DAYS = 90
        const val SESSION_RETENTION_DAYS = 7L
        const val ONBOARDING_SECTION_SEPARATOR = "|"
        const val ONBOARDING_VALUE_SEPARATOR = "="
        const val ONBOARDING_ANSWER_SEPARATOR = ","
    }
}