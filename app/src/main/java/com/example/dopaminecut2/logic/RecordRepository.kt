package com.example.dopaminecut2.logic

import com.example.dopaminecut2.model.DailyRecord
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class RecordRepository {
    /** Firestore 데이터베이스 인스턴스 접근용 객체 */
    private val db = FirebaseFirestore.getInstance()

    /** 사용자의 일일 통계 데이터가 저장되는 최상위 컬렉션(DailyRecords) 참조 */
    private val recordsCollection = db.collection("DailyRecords")

    /** * 사용자 ID(uid)와 날짜(date)를 조합하여 해당 일자의 고유 문서 ID를 생성함
     * (예: "user123_2026-03-22" 형태로 저장하여 날짜별 조회 용이)
     */
    private fun getDocId(uid: String, date: String): String = "${uid}_${date}"

    /**
     * 자정에 로컬에서 집계된 하루치 전체 데이터를 DB(Firestore)에 최종 전송함
     * * @param uid 사용자 고유 식별자
     * @param date 기준 날짜 (예: "2026-03-22")
     * @param totalTime 오늘 하루 전체 앱 사용 시간
     * @param appTimes 각 앱별 사용 시간 통계 (패키지명 : 누적 시간)
     * @param appCounts 각 앱별 숏폼 시청 횟수 통계 (패키지명 : 시청 횟수)
     * @param isGoalAchieved 사용자가 설정한 목표 달성 여부
     * @param onComplete 업데이트 성공/실패 여부를 호출부에 알려주는 콜백 함수
     */
    fun syncDailyStats(
        uid: String,
        date: String,
        totalTime: Long,
        appTimes: Map<String, Long>,
        appCounts: Map<String, Int>,
        isGoalAchieved: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val docId = getDocId(uid, date)

        // DB에 업데이트할 필드들을 묶어 한 번의 요청으로 처리함
        val updates = hashMapOf<String, Any>(
            "totalUsageTime" to totalTime,
            "appUsageTime" to appTimes,
            "appUsageCount" to appCounts,
            "isGoalAchieved" to isGoalAchieved// 목표 달성 여부 추가 필요
        )

        recordsCollection.document(docId).update(updates)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

}