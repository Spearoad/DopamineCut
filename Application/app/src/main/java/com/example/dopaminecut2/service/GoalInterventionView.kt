package com.example.dopaminecut2.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.OverlayGoalInterventionBinding
import com.example.dopaminecut2.domain.GoalMetric
import com.example.dopaminecut2.domain.InterventionMode
import com.example.dopaminecut2.domain.ManagedGoal

/** Presentation only. No goal changes, snooze timers or usage bookkeeping. */
internal object GoalInterventionView {
    fun create(context: Context, goal: ManagedGoal, finish: () -> Unit,
        extend: () -> Unit, settings: () -> Unit): OverlayGoalInterventionBinding {
        val themed = ContextThemeWrapper(context, R.style.Theme_Dopaminecut2)
        val binding = OverlayGoalInterventionBinding.inflate(LayoutInflater.from(themed))
        binding.interventionMetric.setText(when (goal.metric) {
            GoalMetric.DAILY_TIME -> R.string.intervention_metric_time
            GoalMetric.DAILY_COUNT -> R.string.intervention_metric_count
            GoalMetric.APP_TIME -> R.string.intervention_metric_app
        })
        binding.interventionTarget.text = themed.getString(
            if (goal.metric == GoalMetric.DAILY_COUNT) R.string.intervention_daily_count else R.string.intervention_daily_minutes,
            goal.target)
        val apps = goal.platforms.sortedBy { it.ordinal }.joinToString { it.displayName }
        binding.interventionScope.text = themed.getString(
            if (goal.metric == GoalMetric.APP_TIME) R.string.intervention_scope_app else R.string.intervention_scope_shortform, apps)
        binding.interventionExtend.isVisible = goal.intervention == InterventionMode.CONFIRM
        binding.interventionFinish.setOnClickListener { finish() }
        binding.interventionExtend.setOnClickListener { extend() }
        binding.interventionSettings.setOnClickListener { settings() }
        // Transparent modal touch guard: outside taps never dismiss the goal or reach YouTube.
        // Keep the window non-focusable so Android navigation and foreground detection still work.
        binding.root.isClickable = true
        binding.root.setOnClickListener { }
        return binding
    }
}
