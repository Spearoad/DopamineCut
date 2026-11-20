package com.example.dopaminecut2.ui.stats

import com.example.dopaminecut2.data.local.GoalPlan
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.domain.DayQuality
import com.example.dopaminecut2.domain.GoalMetric
import com.example.dopaminecut2.domain.ManagedGoal
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.ui.common.ContentState
import java.time.LocalDate
import java.time.YearMonth

enum class StatsPeriod { TODAY, SEVEN_DAYS, THIRTY_DAYS, CUSTOM }
enum class StatsSection { USAGE, CONTENT, GOALS }
enum class StatsMetric { TIME, COUNT }

data class StatsPlatformUi(
    val displayName: String,
    val appTimeSec: Long,
    val shortformTimeSec: Long,
    val shortformCount: Long
)

data class StatsCategoryUi(
    val id: String,
    val label: String,
    val count: Long,
    val durationSec: Long
)

data class StatsDayUi(
    val date: String,
    val statistics: DailyStatistics?
)

data class StatsDashboard(
    val platforms: List<StatsPlatformUi>,
    val hourlyCounts: List<Long>,
    val hourlyTimesSec: List<Long> = List(24) { 0L },
    val categories: List<StatsCategoryUi>,
    val days: List<StatsDayUi>,
    val recordedDays: Int,
    val averageDays: Int,
    val totalAppTimeSec: Long,
    val totalShortformTimeSec: Long,
    val totalShortformCount: Long,
    val averageAppTimeSec: Double?,
    val averageShortformTimeSec: Double?,
    val averageCount: Double?,
    val previousTimeAverage: Double?,
    val previousCountAverage: Double?,
    val previousAverageDays: Int,
    val categorySampleLimited: Boolean
)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.SEVEN_DAYS,
    val section: StatsSection = StatsSection.USAGE,
    val metric: StatsMetric = StatsMetric.TIME,
    val platform: SupportedPlatform? = null,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val today: String = "",
    val selectedDate: String? = null,
    val selectedHour: Int? = null,
    val calendarMonth: YearMonth = YearMonth.now(),
    val calendarSelectedDate: String = "",
    val calendarDays: List<DailyStatistics> = emptyList(),
    val goalDays: List<DailyStatistics> = emptyList(),
    val goalEvidenceLoaded: Boolean = false,
    val goals: List<ManagedGoal> = emptyList(),
    val plans: Map<GoalMetric, GoalPlan> = emptyMap(),
    val quality: List<DayQuality> = emptyList(),
    val goalEvidenceError: String? = null,
    val message: String? = null,
    val content: ContentState<StatsDashboard> = ContentState.Loading
)

sealed interface StatsAction {
    data class SelectPeriod(val period: StatsPeriod) : StatsAction
    data class SelectRange(val start: LocalDate, val end: LocalDate) : StatsAction
    data class SelectPlatform(val platform: SupportedPlatform?) : StatsAction
    data class SelectMetric(val metric: StatsMetric) : StatsAction
    data class SelectSection(val section: StatsSection) : StatsAction
    data class SelectDate(val date: String) : StatsAction
    data class SelectHour(val hour: Int) : StatsAction
    data class ShiftCalendarMonth(val deltaMonths: Long) : StatsAction
    data class SelectCalendarDate(val date: String) : StatsAction
    data object JumpCalendarToday : StatsAction
    data object ClearMessage : StatsAction
    data object Retry : StatsAction
}