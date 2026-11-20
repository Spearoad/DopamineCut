package com.example.dopaminecut2.ui.goal

typealias GoalMetric = com.example.dopaminecut2.domain.GoalMetric

typealias GoalIntervention = com.example.dopaminecut2.domain.InterventionMode

data class GoalEditorUiState(
    val platformsByMetric: Map<GoalMetric, Set<String>> = emptyMap(),
    val interventionsByMetric: Map<GoalMetric, GoalIntervention> = emptyMap(),
    val breakMinutesText: String = "0",
    val insights: com.example.dopaminecut2.statistics.InsightSnapshot? = null,
    val metric: GoalMetric = GoalMetric.DAILY_TIME,
    val valueText: String = "",
    val selectedPlatforms: Set<String> = emptySet(),
    val intervention: GoalIntervention = GoalIntervention.RECORD_ONLY,
    val validationMessage: String? = null,
    val availabilityMessage: String = "기기에 먼저 저장하고 Firebase에 동기화합니다. 개입에는 접근성 연결이 필요하며 알림 방식에는 알림 허용이 필요합니다.",
    val selectedMetrics: Set<GoalMetric> = setOf(GoalMetric.DAILY_TIME),
    val values: Map<GoalMetric, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val isEditing: Boolean = false,
    val canSubmit: Boolean = false
)

sealed interface GoalEditorAction {
    data class SetBreakMinutes(val text: String) : GoalEditorAction
    data class EditCard(val metric: GoalMetric) : GoalEditorAction
    data class Recommend(val metric: GoalMetric, val level: com.example.dopaminecut2.domain.ReductionLevel) : GoalEditorAction
    data class SelectMetric(val metric: GoalMetric) : GoalEditorAction
    data class UpdateValue(val value: String) : GoalEditorAction
    data class TogglePlatform(val platformId: String) : GoalEditorAction
    data class SetPlatforms(val metric: GoalMetric, val platforms: Set<String>) : GoalEditorAction
    data class SelectIntervention(val intervention: GoalIntervention) : GoalEditorAction
    data class SetMetricEnabled(val metric: GoalMetric, val enabled: Boolean) : GoalEditorAction
    data class UpdateMetricValue(val metric: GoalMetric, val value: String) : GoalEditorAction
    data object Save : GoalEditorAction
}