package com.example.dopaminecut2.statistics

import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.CategoryUsage
import com.example.dopaminecut2.data.model.DailyStatistics

/** 홈과 기간별 통계가 함께 사용하는 집계 결과. 화면 표시 단위는 UI에서 변환한다. */
data class UsageSummary(
    val appUsage: Map<String, AppUsage>,
    val categoryUsage: Map<String, CategoryUsage>,
    val hourlyShortformCount: List<Long>,
    val hourlyShortformTimeSec: List<Long> = List(24) { 0L }
) {
    val totalAppTimeSec: Long get() = appUsage.values.sumOf(AppUsage::runTimeSec)
    val totalShortformTimeSec: Long get() = appUsage.values.sumOf(AppUsage::shortformTimeSec)
    val totalShortformCount: Long get() = appUsage.values.sumOf(AppUsage::shortformCount)

    companion object {
        fun from(statistics: Iterable<DailyStatistics>): UsageSummary {
            val apps = linkedMapOf<String, AppUsage>()
            val categories = linkedMapOf<String, CategoryUsage>()
            val hours = MutableList(24) { 0L }
            val timeHours = MutableList(24) { 0L }

            statistics.forEach { day ->
                day.appUsage.forEach { (platform, usage) ->
                    val current = apps[platform] ?: AppUsage()
                    val mergedHourlyTime = current.hourlyShortformTimeSec.toMutableMap()
                    usage.hourlyShortformTimeSec.forEach { (hour, sec) ->
                        mergedHourlyTime[hour] = (mergedHourlyTime[hour] ?: 0L) + sec
                        hour.toIntOrNull()?.takeIf { it in timeHours.indices }?.let { index ->
                            timeHours[index] += sec
                        }
                    }
                    val mergedHourlyCount = current.hourlyShortformCount.toMutableMap()
                    usage.hourlyShortformCount.forEach { (hour, count) ->
                        mergedHourlyCount[hour] = (mergedHourlyCount[hour] ?: 0L) + count
                    }
                    apps[platform] = AppUsage(
                        runTimeSec = current.runTimeSec + usage.runTimeSec,
                        shortformTimeSec = current.shortformTimeSec + usage.shortformTimeSec,
                        shortformCount = current.shortformCount + usage.shortformCount,
                        hourlyShortformTimeSec = mergedHourlyTime.toMap(),
                        hourlyShortformCount = mergedHourlyCount.toMap()
                    )
                }
                day.categoryUsage.forEach { (category, usage) ->
                    val current = categories[category] ?: CategoryUsage()
                    categories[category] = CategoryUsage(
                        count = current.count + usage.count,
                        durationSec = current.durationSec + usage.durationSec
                    )
                }
                if (day.hourlyShortformCount.isNotEmpty()) {
                    day.hourlyShortformCount.forEach { (hour, count) ->
                        hour.toIntOrNull()?.takeIf { it in hours.indices }?.let { index ->
                            hours[index] += count
                        }
                    }
                } else {
                    day.appUsage.values.forEach { usage ->
                        usage.hourlyShortformCount.forEach { (hour, count) ->
                            hour.toIntOrNull()?.takeIf { it in hours.indices }?.let { index ->
                                hours[index] += count
                            }
                        }
                    }
                }
            }
            return UsageSummary(
                appUsage = apps.toMap(),
                categoryUsage = categories.toMap(),
                hourlyShortformCount = hours.toList(),
                hourlyShortformTimeSec = timeHours.toList()
            )
        }
    }
}