package com.example.dopaminecut2.data.repository

import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.model.DopamineLog
import com.example.dopaminecut2.data.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepositoryInterface {
    /**
     * 앱 실행 시 유저의 기본 정보와 인벤토리 현황을 불러오는 함수
     * @param userId Firebase Auth 고유 식별자
     */
    suspend fun getUserInfo(userId: String): Result<User>

    /**
     * 유저 정보의 실시간 변경(아이템 사용 등)을 감지하기 위한 스트림
     */
    fun getUserInfoFlow(userId: String): Flow<User>

    /**
     * 유저가 팝업에서 수정한 차단 카테고리 또는 목표를 동기화하는 함수
     */
    // suspend fun updateTargetSettings(userId: String, restrictions: List<String>): Result<Unit>

    // 시간, 횟수도 넘길 수 있도록 선언
    suspend fun updateTargetSettings(
        userId: String,
        timeLimit: Int,
        countLimit: Int,
        tags: List<String>
    ): Result<Unit>

    /**
     * ViewTracker가 전달한 숏폼 시청 기록과 AI 카테고리 분류 결과를 서버에 업로드
     */
    suspend fun addDopamineLog(log: DopamineLog): Result<Unit>

    /**
     * 측정된 앱 실행 시간과 숏폼 횟수를 daily_statistics DB에 실시간 합산
     * @param date "YYYYMMDD" 형식의 오늘 날짜
     * @param platform "youtube", "instagram" 등 타겟 앱
     */
    suspend fun incrementAppUsage(
        userId: String,
        date: String,
        platform: String,
        runTimeSec: Long,
        shortformCount: Long
    ): Result<Unit>

    fun getDailyStatisticsFlow(userId: String, date: String): Flow<DailyStatistics?>
    fun getDopamineLogsFlow(userId: String): Flow<List<DopamineLog>>
}