package com.example.dopaminecut2.logic

import com.example.dopaminecut2.data.model.AppUsage
import com.example.dopaminecut2.domain.*

enum class GoalThreshold { NEAR, REACHED }
data class GoalInterventionRequest(val goal: ManagedGoal, val threshold: GoalThreshold) {
    val key get() = "${goal.metric.name}_${goal.revision}_${threshold.name}"
}

object ManagedGoalPolicy {
    fun evaluate(goals: List<ManagedGoal>, usage: Map<String, AppUsage>, foreground: SupportedPlatform?,
                 shortformEligible: Boolean, normalVideo: Boolean,
                 countAdmission: Boolean = false): List<GoalInterventionRequest> = goals.mapNotNull { goal ->
        if (goal.status != GoalStatus.ACTIVE || goal.intervention == InterventionMode.RECORD_ONLY || foreground !in goal.platforms) return@mapNotNull null
        if (goal.metric != GoalMetric.APP_TIME && !shortformEligible) return@mapNotNull null
        if (goal.metric == GoalMetric.DAILY_COUNT && !normalVideo) return@mapNotNull null
        val used = goal.platforms.sumOf { platform ->
            val value = usage[platform.storageKey] ?: AppUsage()
            when (goal.metric) {
                GoalMetric.APP_TIME -> value.runTimeSec
                GoalMetric.DAILY_TIME -> value.shortformTimeSec
                GoalMetric.DAILY_COUNT -> value.shortformCount
            }.coerceAtLeast(0)
        }
        val target = goal.target.toLong() * if (goal.metric == GoalMetric.DAILY_COUNT) 1 else 60
        // Count limits are checked BEFORE admitting a new normal session. An already
        // admitted final video remains allowed, including after temporary CV recovery.
        val reached = if (goal.metric == GoalMetric.DAILY_COUNT) countAdmission && used >= target else used >= target
        val threshold = when {
            reached -> GoalThreshold.REACHED
            goal.intervention == InterventionMode.NOTIFY && target > 0 && used * 5 >= target * 4 -> GoalThreshold.NEAR
            else -> return@mapNotNull null
        }
        GoalInterventionRequest(goal, threshold)
    }.sortedByDescending { when (it.goal.intervention) { InterventionMode.RESTRICT -> 3; InterventionMode.CONFIRM -> 2; else -> 1 } }
}
