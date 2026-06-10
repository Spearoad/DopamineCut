package com.example.dopaminecut2.data.repository

import com.example.dopaminecut2.data.local.DataStoreManager
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.remote.FirebaseDataSource
import com.example.dopaminecut2.data.model.User
import com.example.dopaminecut2.data.model.DopamineLog
import kotlinx.coroutines.flow.Flow

class UserRepository(
    private val remoteDataSource: FirebaseDataSource,
    private val localDataSource: DataStoreManager
) : UserRepositoryInterface {

    override suspend fun getUserInfo(userId: String): Result<User> {
        return try {
            // Firebase에서 데이터 가져오기 요청
            val user = remoteDataSource.fetchUser(userId)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getUserInfoFlow(userId: String): Flow<User> {
        // 실시간 변경 감지는 Flow로 반환
        return remoteDataSource.getUserStream(userId)
    }

    override suspend fun updateTargetSettings(userId: String, timeLimit: Int, countLimit: Int, tags: List<String>): Result<Unit> {
            return try {
                // 1. Firebase 서버에 업데이트
                remoteDataSource.updateUserTargetSettings(userId, timeLimit, countLimit, tags)
                // 2. 로컬(기기 내부) 데이터도 동기화
                localDataSource.saveRestrictionsLocally(tags)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override suspend fun addDopamineLog(log: DopamineLog): Result<Unit> {
            return try {
                remoteDataSource.insertDopamineLog(log)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override suspend fun incrementAppUsage(
            userId: String,
            date: String,
            platform: String,
            runTimeSec: Long,
            shortformCount: Long
        ): Result<Unit> {
            return try {
                // FieldValue.increment() 로직이 들어있는 remoteDataSource 함수 호출
                remoteDataSource.incrementAppUsageData(userId, date, platform, runTimeSec, shortformCount)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override fun getDailyStatisticsFlow(userId: String, date: String): Flow<DailyStatistics?> {
        return remoteDataSource.getDailyStatisticsStream(userId, date)
    }

    override fun getDopamineLogsFlow(userId: String): Flow<List<DopamineLog>> {
        return remoteDataSource.getDopamineLogsStream(userId)
    }
}