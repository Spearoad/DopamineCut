package com.example.dopaminecut2.ui.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dopaminecut2.auth.AuthRepository
import com.example.dopaminecut2.data.local.GoalStore
import com.example.dopaminecut2.data.local.OnboardingStore
import com.example.dopaminecut2.data.local.OnboardingFlowPolicy
import com.example.dopaminecut2.domain.ManagedGoal
import com.example.dopaminecut2.domain.SupportedPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GoalEditorViewModel(
    private val authRepository: AuthRepository? = null,
    private val onboardingStore: OnboardingStore? = null,
    private val goalStore: GoalStore? = null,
    private val editMetric: GoalMetric? = null,
    private val insightsRepository: com.example.dopaminecut2.statistics.HabitInsightsRepository? = null,
    private val habitStore: com.example.dopaminecut2.data.local.HabitStore? = null,
    initialMetrics: Set<GoalMetric>? = null
) : ViewModel() {
    private val ownerId = authRepository?.currentUserId()
    private val _uiState = MutableStateFlow(
        GoalEditorUiState(
            isLoading = goalStore != null,
            isEditing = editMetric != null,
            selectedMetrics = initialMetrics?.takeIf { it.isNotEmpty() } ?: setOf(GoalMetric.DAILY_TIME),
            metric = initialMetrics?.minByOrNull { it.ordinal } ?: GoalMetric.DAILY_TIME
        )
    )
    val uiState: StateFlow<GoalEditorUiState> = _uiState.asStateFlow()
    private var existing: List<ManagedGoal> = emptyList()
    private var loaded = goalStore == null

    init {
        if (goalStore != null) viewModelScope.launch {
            try {
                val userId = requireNotNull(ownerId) { "로그인 정보가 없습니다." }
                existing = goalStore.observeGoals(userId).first()
                val mode = onboardingStore?.observeOnboarding(userId)?.first()?.let(OnboardingFlowPolicy::intervention)
                    ?: GoalIntervention.RECORD_ONLY
                require(authRepository?.currentUserId() == userId) { "계정이 변경됐습니다. 화면을 다시 열어 주세요." }
                val goal = editMetric?.let { metric ->
                    requireNotNull(existing.firstOrNull { it.metric == metric }) { "수정할 목표가 없습니다." }
                }
                val defaultPlatforms = ManagedGoal.SUPPORTED.map { it.name }.toSet()
                val resolvedPlatforms = GoalMetric.entries.associateWith { m ->
                    existing.firstOrNull { it.metric == m }
                        ?.platforms
                        ?.map { it.name }
                        ?.toSet()
                        ?.takeIf { it.isNotEmpty() }
                        ?: defaultPlatforms
                }
                val existingValues = existing.associate { it.metric to it.target.toString() }
                loaded = true
                _uiState.value = if (goal != null) {
                    _uiState.value.copy(
                        isLoading = false,
                        metric = goal.metric,
                        selectedMetrics = setOf(goal.metric),
                        values = mapOf(goal.metric to goal.target.toString()),
                        valueText = goal.target.toString(),
                        selectedPlatforms = goal.platforms.map { it.name }.toSet(),
                        intervention = goal.intervention
                    )
                } else {
                    val focused = _uiState.value.metric
                    _uiState.value.copy(
                        isLoading = false,
                        values = existingValues,
                        valueText = existingValues[focused].orEmpty(),
                        selectedPlatforms = resolvedPlatforms[focused].orEmpty(),
                        intervention = existing.firstOrNull { it.metric == focused }?.intervention ?: mode
                    )
                }
                _uiState.value = _uiState.value.copy(
                    platformsByMetric = resolvedPlatforms,
                    interventionsByMetric = GoalMetric.entries.associateWith { m ->
                        existing.firstOrNull { it.metric == m }?.intervention ?: mode
                    }
                )
                refreshValidation()
                _uiState.value = _uiState.value.copy(
                    breakMinutesText = habitStore?.plans(userId)?.get(GoalMetric.DAILY_TIME)?.continuousBreakMinutes?.toString() ?: "0"
                )
                runCatching { insightsRepository?.load(userId) }.onSuccess { insights ->
                    if (authRepository?.currentUserId() == userId) _uiState.value = _uiState.value.copy(insights = insights)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(availabilityMessage = "추천 기준 조회 실패: ${it.message}. 직접 목표를 설정할 수 있어요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    validationMessage = error.message ?: "목표를 불러오지 못했습니다. 화면을 다시 열어 주세요."
                )
            }
        }
    }

    fun onAction(action: GoalEditorAction) {
        if (_uiState.value.isLoading || _uiState.value.isSaving) return
        if (action == GoalEditorAction.Save) {
            save()
            return
        }
        val current = _uiState.value
        _uiState.value = when (action) {
            is GoalEditorAction.SetBreakMinutes -> current.copy(breakMinutesText = action.text)
            is GoalEditorAction.EditCard -> current.copy(
                metric = action.metric,
                valueText = current.values[action.metric].orEmpty(),
                selectedPlatforms = current.platformsByMetric[action.metric].orEmpty(),
                intervention = current.interventionsByMetric[action.metric] ?: GoalIntervention.RECORD_ONLY
            )
            is GoalEditorAction.Recommend -> {
                val platforms = current.platformsByMetric[action.metric].orEmpty().map { SupportedPlatform.valueOf(it) }.toSet()
                val baseline = current.insights?.baseline(action.metric, platforms)
                val target = baseline?.target(action.level)
                val max = if (action.metric == GoalMetric.DAILY_COUNT) 999 else 1440
                if (target != null && target <= max) current.copy(values = current.values + (action.metric to target.toString()))
                else current.copy(validationMessage = "유효 3일의 기록과 적용 앱을 확인해 주세요.")
            }
            is GoalEditorAction.SelectMetric -> current.copy(
                metric = action.metric,
                selectedMetrics = setOf(action.metric),
                valueText = current.values[action.metric].orEmpty()
            )
            is GoalEditorAction.UpdateValue -> current.copy(
                valueText = action.value,
                values = current.values + (current.metric to action.value)
            )
            is GoalEditorAction.UpdateMetricValue -> current.copy(
                values = current.values + (action.metric to action.value),
                valueText = if (action.metric == current.metric) action.value else current.valueText
            )
            is GoalEditorAction.SetMetricEnabled -> {
                val selected = current.selectedMetrics.toMutableSet().apply {
                    if (action.enabled) add(action.metric) else remove(action.metric)
                }
                val focused = current.metric.takeIf { it in selected } ?: selected.minByOrNull { it.ordinal } ?: current.metric
                current.copy(
                    selectedMetrics = selected,
                    metric = focused,
                    selectedPlatforms = current.platformsByMetric[focused].orEmpty(),
                    intervention = current.interventionsByMetric[focused] ?: current.intervention
                )
            }
            is GoalEditorAction.SelectIntervention -> current.copy(
                intervention = action.intervention,
                interventionsByMetric = current.interventionsByMetric + (current.metric to action.intervention)
            )
            is GoalEditorAction.TogglePlatform -> {
                val selected = current.selectedPlatforms.toMutableSet().apply {
                    if (!add(action.platformId)) remove(action.platformId)
                }
                current.copy(
                    selectedPlatforms = selected,
                    platformsByMetric = current.platformsByMetric + (current.metric to selected)
                )
            }
            is GoalEditorAction.SetPlatforms -> current.copy(
                selectedPlatforms = if (action.metric == current.metric) action.platforms else current.selectedPlatforms,
                platformsByMetric = current.platformsByMetric + (action.metric to action.platforms)
            )
            GoalEditorAction.Save -> current
        }
        refreshValidation()
    }

    private fun validate(state: GoalEditorUiState): String? {
        if (state.breakMinutesText.toIntOrNull() !in 0..180) return "연속 시청 휴식 안내는 0~180분으로 입력해 주세요. 0은 끄기입니다."
        if (state.selectedMetrics.isEmpty()) return "목표를 하나 이상 선택해 주세요."
        for (metric in state.selectedMetrics) {
            val apps = state.platformsByMetric[metric].orEmpty()
            if (apps.isEmpty() || apps.any { id -> ManagedGoal.SUPPORTED.none { it.name == id } }) {
                return "목표마다 적용할 앱을 하나 이상 선택해 주세요."
            }
            val text = state.values[metric].orEmpty()
            val value = text.toIntOrNull() ?: return if (text.isBlank()) null else "숫자 범위를 확인해 주세요."
            if (value !in 0..if (metric == GoalMetric.DAILY_COUNT) 999 else 1440) {
                return "목표는 영상 수 0~999개, 시간 0~1440분으로 입력해 주세요."
            }
        }
        return null
    }

    private fun refreshValidation() {
        val state = _uiState.value
        val message = validate(state)
        _uiState.value = state.copy(
            validationMessage = message,
            canSubmit = loaded && goalStore != null && ownerId != null &&
                    !state.isLoading && !state.isSaving && message == null && state.selectedMetrics.isNotEmpty() &&
                    state.selectedMetrics.all { state.values[it]?.toIntOrNull() != null }
        )
    }

    private fun save() {
        if (!_uiState.value.canSubmit) return
        val draft = _uiState.value
        val userId = ownerId ?: return
        viewModelScope.launch {
            _uiState.value = draft.copy(isSaving = true, canSubmit = false)
            try {
                require(authRepository?.currentUserId() == userId) { "계정이 변경됐습니다. 화면을 다시 열어 주세요." }
                val goals = draft.selectedMetrics.map { metric ->
                    val old = existing.firstOrNull { it.metric == metric }
                    ManagedGoal(
                        metric,
                        requireNotNull(draft.values[metric]?.toIntOrNull()),
                        draft.platformsByMetric[metric].orEmpty().map { SupportedPlatform.valueOf(it) }.toSet(),
                        draft.interventionsByMetric[metric] ?: draft.intervention,
                        status = old?.status ?: com.example.dopaminecut2.domain.GoalStatus.ACTIVE,
                        revision = old?.revision ?: 0L
                    )
                }
                requireNotNull(goalStore).saveGoals(userId, goals)
                val persisted = goalStore.observeGoals(userId).first()
                existing = persisted
                persisted.filter { it.metric in draft.selectedMetrics }.forEach { goal ->
                    habitStore?.savePlan(
                        userId,
                        com.example.dopaminecut2.data.local.GoalPlan(
                            goal.metric,
                            goal.revision,
                            java.time.LocalDate.now().format(com.example.dopaminecut2.domain.HabitInsightsPolicy.format),
                            draft.insights?.baseline(goal.metric, goal.platforms),
                            continuousBreakMinutes = if (goal.metric == GoalMetric.DAILY_TIME) draft.breakMinutesText.toInt() else 0
                        )
                    )
                }
                if (authRepository?.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (authRepository?.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(isSaving = false)
                    refreshValidation()
                    _uiState.value = _uiState.value.copy(validationMessage = error.message ?: "저장하지 못했습니다. 다시 시도해 주세요.")
                }
            }
        }
    }
}