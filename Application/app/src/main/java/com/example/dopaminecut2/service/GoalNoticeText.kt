package com.example.dopaminecut2.service

import android.content.Context
import com.example.dopaminecut2.R
import com.example.dopaminecut2.domain.GoalMetric
import com.example.dopaminecut2.logic.GoalInterventionRequest
import com.example.dopaminecut2.logic.GoalThreshold

/** Short notification copy shared by every goal type. Delivery policy is unchanged. */
internal object GoalNoticeText {
    data class Copy(val title: String, val body: String)

    fun goal(context: Context, request: GoalInterventionRequest): Copy {
        val metric = context.getString(when (request.goal.metric) {
            GoalMetric.DAILY_TIME -> R.string.intervention_metric_time
            GoalMetric.DAILY_COUNT -> R.string.intervention_metric_count
            GoalMetric.APP_TIME -> R.string.intervention_metric_app
        })
        val target = context.getString(
            if (request.goal.metric == GoalMetric.DAILY_COUNT) R.string.intervention_daily_count
            else R.string.intervention_daily_minutes, request.goal.target)
        val apps = request.goal.platforms.sortedBy { it.ordinal }.joinToString { it.displayName }
        return Copy(context.getString(
            if (request.threshold == GoalThreshold.NEAR) R.string.intervention_notice_near
            else R.string.intervention_notice_reached, metric), "$apps · $target")
    }

    fun rest(context: Context, minutes: Int) = Copy(
        context.getString(R.string.intervention_notice_rest),
        context.getString(R.string.intervention_notice_continuous, minutes))
}
