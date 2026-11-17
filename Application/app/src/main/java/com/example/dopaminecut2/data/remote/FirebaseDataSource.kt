package com.example.dopaminecut2.data.remote

import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.CategoryUsage
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.model.User
import com.example.dopaminecut2.statistics.UsageSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.Source
import com.example.dopaminecut2.statistics.UsageSyncIdentity
import com.example.dopaminecut2.statistics.UsageSyncConflictException

interface RemoteUserDataSource {
    suspend fun fetchUser(userId: String): User
    suspend fun fetchMonthlyStatistics(
        userId: String,
        monthId: String
    ): Map<String, DailyStatistics>
    suspend fun writeUsageSnapshots(snapshots: List<UsageSnapshot>)
    suspend fun syncUsageSnapshots(snapshots: List<UsageSnapshot>): List<UsageSnapshot> {
        writeUsageSnapshots(snapshots)
        return snapshots
    }
}

class FirebaseDataSource(
    private val firestore: FirebaseFirestore,
    private val functions: com.google.firebase.functions.FirebaseFunctions =
        com.google.firebase.functions.FirebaseFunctions.getInstance("asia-northeast3"),
    private val currentUser: () -> String? = { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }
) : RemoteUserDataSource {
    override suspend fun fetchUser(userId: String): User {
        val snapshot = firestore.collection(FirestoreCollections.USERS).document(userId).get().await()
        if (!snapshot.exists()) error("유저 정보를 찾을 수 없습니다.")
        return snapshot.toUser(userId)
    }

    override suspend fun fetchMonthlyStatistics(
        userId: String,
        monthId: String
    ): Map<String, DailyStatistics> {
        require(MONTH_PATTERN.matches(monthId))
        val snapshot = firestore.collection(FirestoreCollections.USERS)
            .document(userId)
            .collection(FirestoreCollections.MONTHS)
            .document(monthId)
            .get(Source.SERVER)
            .await()
        val days = snapshot.get(FirestoreFields.DAYS).asStringMap()
        return days.mapNotNull { (dayKey, rawDay) ->
            val day = dayKey.removePrefix(DAY_PREFIX)
            if (!DAY_PATTERN.matches(day)) return@mapNotNull null
            val date = monthId + day
            val data = rawDay.asStringMap()
            date to data.toDailyStatistics(date).copy(remoteRevision = (data["revision"] as? Number)?.toLong() ?: 0L)
        }.toMap()
    }

    override suspend fun writeUsageSnapshots(snapshots: List<UsageSnapshot>) {
        if (syncUsageSnapshots(snapshots).size != snapshots.size) throw UsageSyncConflictException()
    }

    override suspend fun syncUsageSnapshots(snapshots: List<UsageSnapshot>): List<UsageSnapshot> {
        if (snapshots.isEmpty()) return emptyList()
        val uid = snapshots.first().userId
        require(snapshots.size <= 31 && snapshots.all { it.userId == uid })
        require(currentUser() == uid) { "로그인 계정이 변경됐습니다." }
        val items = snapshots.map { snapshot ->
            require(DATE_PATTERN.matches(snapshot.date))
            val data = UsageSyncIdentity.payload(snapshot)
            mapOf("date" to snapshot.date, "baseRevision" to snapshot.baseRemoteRevision,
                "revision" to snapshot.revision, "syncId" to UsageSyncIdentity.id(snapshot, data),
                "baseDigest" to snapshot.baseRemoteDigest, "data" to data)
        }
        val response = functions.getHttpsCallable("syncUsageBatch").call(mapOf(
            "apiVersion" to 1, "uid" to uid, "items" to items)).await().data as? Map<*, *>
            ?: error("동기화 응답 형식이 올바르지 않습니다.")
        require(currentUser() == uid) { "로그인 계정이 변경됐습니다. 기기 기록은 보관됩니다." }
        require((response["apiVersion"] as? Number)?.toInt() == 1)
        val ack = response["acknowledged"] as? List<*> ?: error("동기화 결과가 없습니다.")
        val acknowledged = mutableSetOf<String>()
        return ack.map { value ->
            val row = value as? Map<*, *> ?: error("동기화 결과 형식 오류")
            val date = row["date"] as? String ?: error("동기화 날짜 누락")
            require(acknowledged.add(date)) { "동기화 결과 중복" }
            val snapshot = snapshots.single { it.date == date }
            require((row["remoteRevision"] as? Number)?.toLong() == snapshot.baseRemoteRevision + 1)
            snapshot
        }
    }

    private fun User.toRemoteMap(): Map<String, Any> = mapOf(
        FirestoreFields.SCHEMA_VERSION to CURRENT_FIRESTORE_SCHEMA_VERSION,
        FirestoreFields.PROFILE to mapOf(FirestoreFields.NICKNAME to nickname),
        FirestoreFields.GOAL to mapOf(
            FirestoreFields.APP_TIME_LIMIT_MIN to targetTimeMin,
            FirestoreFields.SHORTFORM_LIMIT_COUNT to targetCount,
            FirestoreFields.RESTRICTED_CATEGORIES to restrictions
        ),
        FirestoreFields.CREATED_AT to createdAt,
        FirestoreFields.UPDATED_AT to FieldValue.serverTimestamp()
    )

    private fun DocumentSnapshot.toUser(userId: String): User {
        check(getLong(FirestoreFields.SCHEMA_VERSION) == CURRENT_FIRESTORE_SCHEMA_VERSION) {
            "지원하지 않는 사용자 스키마입니다."
        }
        val profile = get(FirestoreFields.PROFILE)
            .requiredStringMap(FirestoreFields.PROFILE)
        val goal = get(FirestoreFields.GOAL)
            .requiredStringMap(FirestoreFields.GOAL)
        val createdAt = requireNotNull(getDate(FirestoreFields.CREATED_AT)) {
            "사용자 문서에 ${FirestoreFields.CREATED_AT}이 없습니다."
        }
        requireNotNull(getDate(FirestoreFields.UPDATED_AT)) {
            "사용자 문서에 ${FirestoreFields.UPDATED_AT}이 없습니다."
        }
        return User(
            userId = userId,
            nickname = profile[FirestoreFields.NICKNAME] as? String
                ?: error("사용자 문서의 ${FirestoreFields.NICKNAME} 형식이 올바르지 않습니다."),
            createdAt = createdAt,
            restrictions = goal[FirestoreFields.RESTRICTED_CATEGORIES]
                .requiredStringList(FirestoreFields.RESTRICTED_CATEGORIES),
            targetTimeMin = goal.requiredInt(FirestoreFields.APP_TIME_LIMIT_MIN),
            targetCount = goal.requiredInt(FirestoreFields.SHORTFORM_LIMIT_COUNT)
        )
    }

    private fun Map<String, Any?>.toDailyStatistics(date: String): DailyStatistics =
        DailyStatistics(
            date = date,
            deductedScore = longValue(FirestoreFields.DEDUCTED_SCORE),
            appUsage = this[FirestoreFields.APP_USAGE].asStringMap().mapValues { (_, rawUsage) ->
                rawUsage.asStringMap().let { usage ->
                    AppUsage(
                        runTimeSec = usage.longValue(FirestoreFields.APP_TIME_SEC),
                        shortformTimeSec = usage.longValue(FirestoreFields.SHORTFORM_TIME_SEC),
                        shortformCount = usage.longValue(FirestoreFields.SHORTFORM_COUNT)
                    )
                }
            },
            categoryUsage = this[FirestoreFields.CATEGORY_USAGE].asStringMap().mapValues { (_, rawUsage) ->
                rawUsage.asStringMap().let { usage ->
                    CategoryUsage(
                        count = usage.longValue(FirestoreFields.CATEGORY_COUNT),
                        durationSec = usage.longValue(FirestoreFields.CATEGORY_DURATION_SEC)
                    )
                }
            },
            hourlyShortformCount = this[FirestoreFields.HOURLY_SHORTFORM_COUNT]
                .asStringMap()
                .mapValues { (_, count) -> (count as? Number)?.toLong() ?: 0L }
        )

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStringMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.requiredStringMap(fieldName: String): Map<String, Any?> =
        this as? Map<String, Any?>
            ?: error("사용자 문서의 $fieldName 형식이 올바르지 않습니다.")

    private fun Any?.requiredStringList(fieldName: String): List<String> {
        val values = this as? List<*>
            ?: error("사용자 문서의 $fieldName 형식이 올바르지 않습니다.")
        check(values.all { it is String }) {
            "사용자 문서의 $fieldName 형식이 올바르지 않습니다."
        }
        return values.filterIsInstance<String>()
    }

    private fun Map<String, Any?>.longValue(key: String): Long =
        (this[key] as? Number)?.toLong() ?: 0L

    private fun Map<String, Any?>.requiredInt(key: String): Int =
        (this[key] as? Number)?.toInt()
            ?: error("사용자 문서의 $key 형식이 올바르지 않습니다.")

    private companion object {
        const val DAY_PREFIX = "d"
        val DATE_PATTERN = Regex("^\\d{8}$")
        val MONTH_PATTERN = Regex("^\\d{6}$")
        val DAY_PATTERN = Regex("^(0[1-9]|[12]\\d|3[01])$")
    }
}
