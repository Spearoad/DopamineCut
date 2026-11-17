package com.example.dopaminecut2.data.repository

import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.model.User
import com.example.dopaminecut2.data.remote.RemoteUserDataSource
import com.example.dopaminecut2.statistics.UsageSnapshotStore
import com.example.dopaminecut2.statistics.UsageSnapshot
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

class UserRepository(
    private val remoteDataSource: RemoteUserDataSource,
    private val localUsageStore: UsageSnapshotStore
) : UserRepositoryInterface {
    private val users = MutableStateFlow<Map<String, User>>(emptyMap())
    private val monthlyCache = ConcurrentHashMap<String, Map<String, DailyStatistics>>()
    private val cacheTimes = ConcurrentHashMap<String, Long>()
    fun clearStatisticsCache(uid: String) {
        monthlyCache.keys.removeAll { it.startsWith("$uid|") }
        cacheTimes.keys.removeAll { it.startsWith("$uid|") }
    }

    override suspend fun getUserInfo(userId: String): Result<User> {
        users.value[userId]?.let { return Result.success(it) }
        return runCatching { remoteDataSource.fetchUser(userId) }
            .onSuccess { user -> users.value = users.value + (userId to user) }
    }

    override suspend fun getDailyStatistics(
        userId: String,
        date: String
    ): Result<DailyStatistics?> = runCatching {
        val local = localUsageStore.get(userId, date)
        if (local?.dirty == true) return@runCatching local.toDailyStatistics()
        try {
            fetchMonth(userId, date.take(6))[date]?.let {
                localUsageStore.reconcileRemote(userId, it).toDailyStatistics()
            } ?: local?.toDailyStatistics()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (error: Exception) { local?.toDailyStatistics() ?: throw error }
    }

    override suspend fun getStatisticsRange(
        userId: String,
        startDate: String,
        endDate: String
    ): Result<List<DailyStatistics>> = runCatching {
        val start = LocalDate.parse(startDate, DATE_FORMATTER)
        val end = LocalDate.parse(endDate, DATE_FORMATTER)
        require(!end.isBefore(start)) { "통계 시작일이 종료일보다 늦습니다." }

        val local = localUsageStore.getRange(userId, startDate, endDate)
            .associateBy(UsageSnapshot::date)
        val result = local.mapValuesTo(linkedMapOf()) { it.value.toDailyStatistics() }
        var failure: Exception? = null
        for (month in monthsBetween(start, end)) {
            try {
                fetchMonth(userId, month).forEach { (date, remote) ->
                    if (date in startDate..endDate) result[date] =
                        localUsageStore.reconcileRemote(userId, remote).toDailyStatistics()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { failure = error }
        }
        if (result.isEmpty() && failure != null) throw failure
        result.values
            .filter { statistics -> statistics.date in startDate..endDate }
            .sortedBy(DailyStatistics::date)
    }

    private suspend fun fetchMonth(uid: String, month: String): Map<String, DailyStatistics> {
        val key = "$uid|$month"
        val now = System.currentTimeMillis()
        val age = now - (cacheTimes[key] ?: 0L)
        monthlyCache[key]?.takeIf { age in 0L until 15 * 60_000L }?.let { return it }
        return remoteDataSource.fetchMonthlyStatistics(uid, month).also {
            monthlyCache[key] = it
            cacheTimes[key] = now
        }
    }

    private fun monthsBetween(start: LocalDate, end: LocalDate): List<String> {
        val result = mutableListOf<String>()
        var cursor = YearMonth.from(start)
        val last = YearMonth.from(end)
        while (!cursor.isAfter(last)) {
            result += cursor.format(MONTH_FORMATTER)
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}
