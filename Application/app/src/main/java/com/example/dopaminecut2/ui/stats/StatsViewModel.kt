package com.example.dopaminecut2.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dopaminecut2.auth.AuthRepository
import com.example.dopaminecut2.data.local.GoalStore
import com.example.dopaminecut2.data.model.DailyStatistics
import com.example.dopaminecut2.data.repository.UserRepositoryInterface
import com.example.dopaminecut2.domain.HabitInsightsPolicy
import com.example.dopaminecut2.statistics.HabitInsightsRepository
import com.example.dopaminecut2.time.DateIdProvider
import com.example.dopaminecut2.ui.common.ContentState
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StatsViewModel(
    private val repository: UserRepositoryInterface,
    private val authRepository: AuthRepository,
    private val dateIdProvider: DateIdProvider,
    private val goalStore: GoalStore? = null,
    private val insightsRepository: HabitInsightsRepository? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var calendarJob: Job? = null
    private var raw: List<DailyStatistics> = emptyList()
    private var loadedUid: String? = null
    private var requestedUid: String? = null
    private var requestVersion = 0L

    init {
        val currentToday = today()
        val initialMonth = YearMonth.from(currentToday)
        _uiState.value = _uiState.value.copy(
            today = currentToday.format(HabitInsightsPolicy.format),
            calendarMonth = initialMonth,
            calendarSelectedDate = currentToday.format(HabitInsightsPolicy.format)
        )
        selectPeriod(StatsPeriod.SEVEN_DAYS)
        loadCalendarMonth(initialMonth, selectToday = true)
    }

    fun onAction(action: StatsAction) {
        if (loadedUid != null && loadedUid != authRepository.currentUserId() && action != StatsAction.Retry) {
            onAction(StatsAction.Retry)
            return
        }
        when (action) {
            is StatsAction.SelectPeriod -> selectPeriod(action.period)
            is StatsAction.SelectRange -> {
                if (!StatsAnalysis.validRange(action.start, action.end, today())) {
                    _uiState.value = _uiState.value.copy(message = "오늘까지 최대 90일을 선택해 주세요.")
                } else {
                    load(StatsPeriod.CUSTOM, action.start, action.end)
                }
            }
            is StatsAction.SelectPlatform -> {
                _uiState.value = _uiState.value.copy(platform = action.platform, selectedDate = null)
                publish()
            }
            is StatsAction.SelectMetric -> _uiState.value = _uiState.value.copy(metric = action.metric)
            is StatsAction.SelectSection -> _uiState.value = _uiState.value.copy(section = action.section)
            is StatsAction.SelectDate -> _uiState.value = _uiState.value.copy(selectedDate = action.date)
            is StatsAction.SelectHour -> _uiState.value = _uiState.value.copy(selectedHour = action.hour.coerceIn(0, 23))
            is StatsAction.ShiftCalendarMonth -> {
                val currentMonth = _uiState.value.calendarMonth
                val todayMonth = YearMonth.from(today())
                val earliestMonth = todayMonth.minusMonths(11)
                val targetMonth = currentMonth.plusMonths(action.deltaMonths)
                if (!targetMonth.isBefore(earliestMonth) && !targetMonth.isAfter(todayMonth)) {
                    loadCalendarMonth(targetMonth, selectToday = targetMonth == todayMonth)
                }
            }
            is StatsAction.SelectCalendarDate -> {
                _uiState.value = _uiState.value.copy(calendarSelectedDate = action.date)
            }
            StatsAction.JumpCalendarToday -> {
                val todayMonth = YearMonth.from(today())
                loadCalendarMonth(todayMonth, selectToday = true)
            }
            StatsAction.ClearMessage -> _uiState.value = _uiState.value.copy(message = null)
            StatsAction.Retry -> {
                val state = _uiState.value
                if (state.period == StatsPeriod.CUSTOM && state.start != null && state.end != null) {
                    load(state.period, state.start, state.end)
                } else {
                    selectPeriod(state.period)
                }
                loadCalendarMonth(state.calendarMonth, selectToday = false)
            }
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true && requestedUid == authRepository.currentUserId() &&
            _uiState.value.today == today().format(HabitInsightsPolicy.format)
        ) return
        onAction(StatsAction.Retry)
    }

    private fun today() = LocalDate.parse(dateIdProvider.currentDateId(), HabitInsightsPolicy.format)

    private fun selectPeriod(period: StatsPeriod) {
        if (period == StatsPeriod.CUSTOM) return
        val end = today()
        val count = when (period) {
            StatsPeriod.TODAY -> 1L
            StatsPeriod.SEVEN_DAYS -> 7L
            else -> 30L
        }
        load(period, end.minusDays(count - 1), end)
    }

    private fun loadCalendarMonth(month: YearMonth, selectToday: Boolean) {
        calendarJob?.cancel()
        val uid = authRepository.currentUserId() ?: return
        val format = HabitInsightsPolicy.format
        val currentToday = today()
        val todayStr = currentToday.format(format)
        val monthStart = month.atDay(1)
        val monthEnd = minOf(month.atEndOfMonth(), currentToday)
        val defaultSelected = if (selectToday && month == YearMonth.from(currentToday)) {
            todayStr
        } else {
            monthEnd.format(format)
        }
        _uiState.value = _uiState.value.copy(
            calendarMonth = month,
            calendarSelectedDate = defaultSelected
        )
        if (monthEnd.isBefore(monthStart)) {
            _uiState.value = _uiState.value.copy(calendarDays = emptyList())
            return
        }
        calendarJob = viewModelScope.launch {
            val result = repository.getStatisticsRange(uid, monthStart.format(format), monthEnd.format(format))
            if (!currentCoroutineContext().isActive || authRepository.currentUserId() != uid) return@launch
            result.onSuccess { days ->
                _uiState.value = _uiState.value.copy(calendarDays = days)
            }
        }
    }

    private fun load(period: StatsPeriod, start: LocalDate, end: LocalDate) {
        loadJob?.cancel()
        val version = ++requestVersion
        val uid = authRepository.currentUserId()
        requestedUid = uid
        raw = emptyList()
        loadedUid = null
        val todayStr = today().format(HabitInsightsPolicy.format)
        _uiState.value = _uiState.value.copy(
            period = period,
            start = start,
            end = end,
            today = todayStr,
            selectedDate = null,
            selectedHour = null,
            goals = emptyList(),
            plans = emptyMap(),
            quality = emptyList(),
            goalDays = emptyList(),
            goalEvidenceLoaded = false,
            goalEvidenceError = null,
            message = null,
            content = ContentState.Loading
        )
        if (uid == null) {
            _uiState.value = _uiState.value.copy(content = ContentState.Error("로그인 정보가 없습니다.", false))
            return
        }
        val format = HabitInsightsPolicy.format
        val length = ChronoUnit.DAYS.between(start, end) + 1
        loadJob = viewModelScope.launch {
            val result = repository.getStatisticsRange(uid, start.minusDays(length).format(format), end.format(format))
            if (!currentCoroutineContext().isActive || requestVersion != version || authRepository.currentUserId() != uid) return@launch
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.onSuccess {
                raw = it
                loadedUid = uid
                publish()
            }.onFailure {
                _uiState.value = _uiState.value.copy(content = ContentState.Error(it.localizedMessage ?: "통계 조회 실패"))
            }
            if (result.isFailure) return@launch
            try {
                val goals = goalStore?.observeGoals(uid)?.first().orEmpty()
                val snapshot = insightsRepository?.load(uid, localOnly = true)
                if (requestVersion == version && authRepository.currentUserId() == uid) {
                    _uiState.value = _uiState.value.copy(
                        goals = goals,
                        plans = snapshot?.plans.orEmpty(),
                        quality = snapshot?.quality.orEmpty(),
                        goalEvidenceLoaded = true
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestVersion == version && authRepository.currentUserId() == uid) {
                    _uiState.value = _uiState.value.copy(
                        goalEvidenceLoaded = true,
                        goalEvidenceError = "목표 비교 기록을 확인하지 못했어요."
                    )
                }
            }
        }
    }

    private fun publish() {
        val state = _uiState.value
        if (loadedUid == null || loadedUid != authRepository.currentUserId() || state.start == null || state.end == null) return
        val value = StatsAnalysis.dashboard(raw, state.start, state.end, state.today, state.platform)
        val peakHour = value.hourlyCounts.withIndex()
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.index ?: 21
        _uiState.value = state.copy(
            selectedHour = state.selectedHour ?: peakHour,
            goalDays = raw.filter {
                it.date in state.start.format(HabitInsightsPolicy.format)..state.end.format(HabitInsightsPolicy.format)
            },
            content = if (value.recordedDays == 0) {
                ContentState.Empty("선택한 기간에 기록이 없어요.")
            } else {
                ContentState.Data(value)
            }
        )
    }
}