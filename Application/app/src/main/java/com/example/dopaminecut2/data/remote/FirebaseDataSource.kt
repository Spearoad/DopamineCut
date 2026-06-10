package com.example.dopaminecut2.data.remote

import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.model.DopamineLog
import com.example.dopaminecut2.data.model.User
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseDataSource(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    // 유저 정보 조회
    suspend fun fetchUser(userId: String): User {
        val snapshot = firestore.collection("users").document(userId).get().await()
        return snapshot.toObject(User::class.java)
            ?: throw Exception("유저 정보를 찾을 수 없습니다.")
    }

    // 유저 정보 실시간 스트림 (아이템 개수, 점수 변동 등을 화면에 즉각 반영하기 위함)
    fun getUserStream(userId: String): Flow<User> = callbackFlow {
        val listener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val user = snapshot?.toObject(User::class.java)
                if (user != null) {
                    trySend(user).isSuccess
                }
            }
        // 코루틴이 취소되거나 화면이 꺼지면 리스너를 안전하게 해제함
        awaitClose { listener.remove() }
    }

    // 차단 카테고리/목표 설정 업데이트
    /*
    suspend fun updateUserRestrictions(userId: String, restrictions: List<String>) {
        firestore.collection("users").document(userId)
            .update("restrictions", restrictions)
            .await() // 코루틴을 통해 서버 응답이 올 때까지 대기
    }
    */

    // 차단 카테고리/통합 목표 설정 업데이트
    suspend fun updateUserTargetSettings(userId: String, timeLimit: Int, countLimit: Int, tags: List<String>) {
        val updates = hashMapOf<String, Any>(
            "target_time_min" to timeLimit,
            "target_shortform_count" to countLimit,
            "restrictions" to tags
        )
        // .update() 대신 .set(SetOptions.merge())를 쓰면 에러 없이 완벽히 저장됩니다!
        firestore.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .await()
    }

    // 오늘 날짜의 앱 통계 실시간 스트림
    fun getDailyStatisticsStream(userId: String, date: String): Flow<DailyStatistics?> = callbackFlow {
        val documentId = "${userId}_${date}"
        val listener = firestore.collection("daily_statistics").document(documentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val stats = snapshot?.toObject(DailyStatistics::class.java)
                trySend(stats).isSuccess
            }
        awaitClose { listener.remove() }
    }

    // 해당 유저의 도파민 시청 기록 실시간 스트림
    fun getDopamineLogsStream(userId: String): Flow<List<DopamineLog>> = callbackFlow {
        val listener = firestore.collection("dopamine_logs")
            .whereEqualTo("user_id", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val logs = snapshot?.documents?.mapNotNull {
                    it.toObject(DopamineLog::class.java)
                } ?: emptyList()

                trySend(logs).isSuccess
            }
        awaitClose { listener.remove() }
    }

    // 숏폼 시청 로그 저장 (문서 ID는 자동 생성)
    suspend fun insertDopamineLog(log: DopamineLog) {
        firestore.collection("dopamine_logs").add(log).await()
    }

    // 앱 사용량 실시간 누적 (FieldValue.increment 활용)
    suspend fun incrementAppUsageData(
        userId: String,
        date: String,
        platform: String,
        runTimeSec: Long,
        shortformCount: Long
    ) {
        val documentId = "${userId}_${date}"

        val updates = hashMapOf<String, Any>(
            "user_id" to userId,
            "date" to date,
            "app_usage" to hashMapOf(
                platform to hashMapOf(
                    "run_time_sec" to FieldValue.increment(runTimeSec),
                    "shortform_time_sec" to FieldValue.increment(runTimeSec),
                    "shortform_count" to FieldValue.increment(shortformCount)
                )
            )
        )

        // SetOptions.merge()는 기존 데이터를 날리지 않고 깊은 곳(Deep)까지 안전하게 병합해 줍니다.
        firestore.collection("daily_statistics").document(documentId)
            .set(updates, SetOptions.merge())
            .await()
    }
}