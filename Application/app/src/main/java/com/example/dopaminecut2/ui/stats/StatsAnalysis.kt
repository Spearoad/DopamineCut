package com.example.dopaminecut2.ui.stats

import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.domain.HabitInsightsPolicy
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.statistics.UsageSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Missing dates stay null, not zero. Today is excluded from completed-record averages. */
object StatsAnalysis {
    fun validRange(start: LocalDate, end: LocalDate, today: LocalDate) =
        !end.isBefore(start) && !end.isAfter(today) && ChronoUnit.DAYS.between(start, end) < 90

    fun dashboard(
        raw: List<DailyStatistics>,
        start: LocalDate,
        end: LocalDate,
        today: String,
        platform: SupportedPlatform?
    ): StatsDashboard {
        val format = HabitInsightsPolicy.format
        val current = raw.filter { it.date in start.format(format)..end.format(format) }.distinctBy { it.date }

        fun resolveUsageForPlatform(day: DailyStatistics, target: SupportedPlatform): AppUsage? {
            val targetUsage = day.appUsage[target.storageKey] ?: return null

            val resolvedHourlyCount = when {
                targetUsage.hourlyShortformCount.isNotEmpty() -> targetUsage.hourlyShortformCount
                targetUsage.shortformCount <= 0L || day.hourlyShortformCount.isEmpty() -> emptyMap()
                else -> {
                    val activeApps = day.appUsage.values.count { it.shortformCount > 0L }
                    if (activeApps == 1 && targetUsage.shortformCount == day.totalShortformCount) {
                        day.hourlyShortformCount
                    } else {
                        allocateByWeights(targetUsage.shortformCount, day.hourlyShortformCount)
                    }
                }
            }

            val resolvedHourlyTime = when {
                targetUsage.hourlyShortformTimeSec.isNotEmpty() -> targetUsage.hourlyShortformTimeSec
                targetUsage.shortformTimeSec <= 0L || resolvedHourlyCount.isEmpty() -> emptyMap()
                else -> allocateByWeights(targetUsage.shortformTimeSec, resolvedHourlyCount)
            }

            return targetUsage.copy(
                hourlyShortformCount = resolvedHourlyCount,
                hourlyShortformTimeSec = resolvedHourlyTime
            )
        }

        fun scope(values: List<DailyStatistics>) = values.map { day ->
            if (platform == null) {
                val resolvedApps = day.appUsage.mapValues { (key, usage) ->
                    val supported = SupportedPlatform.fromStorageKey(key)
                    if (supported != null) resolveUsageForPlatform(day, supported) ?: usage else usage
                }
                day.copy(appUsage = resolvedApps)
            } else {
                val resolvedUsage = resolveUsageForPlatform(day, platform)
                day.copy(
                    appUsage = if (resolvedUsage != null) mapOf(platform.storageKey to resolvedUsage) else emptyMap(),
                    categoryUsage = emptyMap(),
                    hourlyShortformCount = emptyMap()
                )
            }
        }

        val scoped = scope(current)
        val completed = scoped.filter { it.date < today }
        val length = ChronoUnit.DAYS.between(start, end) + 1
        val previous = scope(raw.filter { it.date in start.minusDays(length).format(format)..start.minusDays(1).format(format) })
            .distinctBy { it.date }
            .filter { it.date < today }
        val summary = UsageSummary.from(scoped)
        fun average(days: List<DailyStatistics>, value: (DailyStatistics) -> Long): Double? =
            days.takeIf { it.isNotEmpty() }?.map { value(it).toDouble() }?.average()
        val byDate = scoped.associateBy { it.date }
        val categories = summary.categoryUsage.entries
            .groupBy { ContentCategory.fromStored(it.key) }
            .map { (category, entries) ->
                StatsCategoryUi(
                    category.id,
                    category.label,
                    entries.sumOf { it.value.count },
                    entries.sumOf { it.value.durationSec }
                )
            }

        val hourlyCounts = if (platform == null) {
            summary.hourlyShortformCount
        } else {
            val map = summary.appUsage[platform.storageKey]?.hourlyShortformCount.orEmpty()
            List(24) { hour -> map[hour.toString().padStart(2, '0')] ?: 0L }
        }

        val hourlyTimesSec = if (platform == null) {
            summary.hourlyShortformTimeSec
        } else {
            val map = summary.appUsage[platform.storageKey]?.hourlyShortformTimeSec.orEmpty()
            List(24) { hour -> map[hour.toString().padStart(2, '0')] ?: 0L }
        }

        return StatsDashboard(
            platforms = summary.appUsage.map { (key, usage) ->
                StatsPlatformUi(
                    SupportedPlatform.fromStorageKey(key)?.displayName ?: key,
                    usage.runTimeSec,
                    usage.shortformTimeSec,
                    usage.shortformCount
                )
            }.sortedByDescending { it.shortformTimeSec },
            hourlyCounts = hourlyCounts,
            hourlyTimesSec = hourlyTimesSec,
            categories = categories,
            days = (0 until length.toInt()).map { offset ->
                start.plusDays(offset.toLong()).format(format).let { StatsDayUi(it, byDate[it]) }
            },
            recordedDays = scoped.size,
            averageDays = completed.size,
            totalAppTimeSec = summary.totalAppTimeSec,
            totalShortformTimeSec = summary.totalShortformTimeSec,
            totalShortformCount = summary.totalShortformCount,
            averageAppTimeSec = average(completed) { it.totalAppTimeSec },
            averageShortformTimeSec = average(completed) { it.totalShortformTimeSec },
            averageCount = average(completed) { it.totalShortformCount },
            previousTimeAverage = average(previous) { it.totalShortformTimeSec },
            previousCountAverage = average(previous) { it.totalShortformCount },
            previousAverageDays = previous.size,
            categorySampleLimited = categories.any { it.id == ContentCategory.UNKNOWN.id } ||
                    categories.sumOf { it.durationSec } != summary.totalShortformTimeSec ||
                    categories.sumOf { it.count } != summary.totalShortformCount
        )
    }

    /** 총합이 정확히 targetTotal과 일치하도록 시간대별 가중치 비율로 정수 배분 */
    private fun allocateByWeights(targetTotal: Long, weights: Map<String, Long>): Map<String, Long> {
        if (targetTotal <= 0L || weights.isEmpty()) return emptyMap()
        val positive = weights.filterValues { it > 0L }
        val weightSum = positive.values.sum()
        if (weightSum <= 0L) return emptyMap()

        data class Bucket(val hour: String, val base: Long, val remainder: Double)

        val buckets = positive.map { (hour, weight) ->
            val exact = targetTotal.toDouble() * weight.toDouble() / weightSum.toDouble()
            val base = exact.toLong()
            Bucket(hour, base, exact - base)
        }
        var remaining = (targetTotal - buckets.sumOf { it.base }).toInt().coerceAtLeast(0)
        val sortedByRemainder = buckets.sortedWith(
            compareByDescending<Bucket> { it.remainder }.thenBy { it.hour }
        )
        val result = linkedMapOf<String, Long>()
        sortedByRemainder.forEachIndexed { idx, bucket ->
            val extra = if (idx < remaining) 1L else 0L
            val value = bucket.base + extra
            if (value > 0L) result[bucket.hour] = value
        }
        return result.toSortedMap()
    }
}