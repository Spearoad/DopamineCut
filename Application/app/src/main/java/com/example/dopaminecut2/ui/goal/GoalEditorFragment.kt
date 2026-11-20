package com.example.dopaminecut2.ui.goal

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.FragmentGoalEditorBinding
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.ui.common.PrototypeUi
import com.example.dopaminecut2.ui.navigation.AppNavigator
import java.util.Calendar
import kotlinx.coroutines.launch

class GoalEditorFragment : Fragment() {
    private var _binding: FragmentGoalEditorBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: GoalEditorViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        val metricId = arguments?.getString(AppNavigator.ARG_GOAL_METRIC)
        ViewModelFactory {
            GoalEditorViewModel(
                dependencies.authRepository,
                dependencies.onboardingStore,
                dependencies.goalStore,
                GoalMetric.entries.firstOrNull { it.name == metricId },
                dependencies.habitInsights,
                dependencies.habitStore,
                arguments?.getStringArrayList(AppNavigator.ARG_SELECTED_METRICS)
                    ?.mapNotNull { id -> GoalMetric.entries.firstOrNull { it.name == id } }
                    ?.toSet()
            )
        }
    }

    private data class MetricScheduleDraft(
        var isCombinedScope: Boolean = true,
        var selectedAppIndex: Int = 0,
        var isWeeklyMode: Boolean = true,
        var dailyTarget: String = "30",
        var bulkTarget: String = "30",
        val dayChecked: BooleanArray = BooleanArray(7) { true },
        val dayTargets: Array<String> = arrayOf("30", "30", "30", "30", "30", "60", "60"),
        var excludeHolidays: Boolean = false
    )

    private val drafts = mutableMapOf<GoalMetric, MetricScheduleDraft>()
    private var rendering = false
    private var initializedFromLoadedState = false

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onAction(GoalEditorAction.Save)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentGoalEditorBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val navigator = AppNavigator(findNavController())
        val appIds = listOf("YOUTUBE", "INSTAGRAM", "KAKAOTALK")
        val appNames = listOf("YouTube", "Instagram", "KakaoTalk")
        val appTabs = listOf(binding.tabAppYoutube, binding.tabAppInstagram, binding.tabAppKakaotalk)
        val dayRowContainers = listOf(
            binding.rowDayMon, binding.rowDayTue, binding.rowDayWed,
            binding.rowDayThu, binding.rowDayFri, binding.rowDaySat, binding.rowDaySun
        )
        val dayRows = listOf(
            Triple(binding.cbDayMon, binding.etDayMon, binding.tvUnitMon),
            Triple(binding.cbDayTue, binding.etDayTue, binding.tvUnitTue),
            Triple(binding.cbDayWed, binding.etDayWed, binding.tvUnitWed),
            Triple(binding.cbDayThu, binding.etDayThu, binding.tvUnitThu),
            Triple(binding.cbDayFri, binding.etDayFri, binding.tvUnitFri),
            Triple(binding.cbDaySat, binding.etDaySat, binding.tvUnitSat),
            Triple(binding.cbDaySun, binding.etDaySun, binding.tvUnitSun)
        )
        val metricTabs = listOf(
            binding.btnMetricTabTime to GoalMetric.DAILY_TIME,
            binding.btnMetricTabCount to GoalMetric.DAILY_COUNT,
            binding.btnMetricTabAppTime to GoalMetric.APP_TIME
        )

        val todayIndex = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }

        fun defaultDraftFor(metric: GoalMetric, existingValue: String?, platforms: Set<String>): MetricScheduleDraft {
            val isCount = metric == GoalMetric.DAILY_COUNT
            val defaultWeekday = if (isCount) "10" else "30"
            val defaultWeekend = if (isCount) "20" else "60"
            val initialVal = existingValue?.takeIf { it.isNotBlank() }
            val singleAppIdx = if (platforms.size == 1) appIds.indexOf(platforms.first()).coerceAtLeast(0) else 0
            return MetricScheduleDraft(
                isCombinedScope = platforms.size != 1,
                selectedAppIndex = singleAppIdx,
                isWeeklyMode = true,
                dailyTarget = initialVal ?: defaultWeekday,
                bulkTarget = initialVal ?: defaultWeekday,
                dayChecked = BooleanArray(7) { true },
                dayTargets = Array(7) { idx ->
                    initialVal ?: if (idx in 0..4) defaultWeekday else defaultWeekend
                },
                excludeHolidays = false
            )
        }

        fun draftFor(metric: GoalMetric): MetricScheduleDraft =
            drafts.getOrPut(metric) {
                val state = viewModel.uiState.value
                defaultDraftFor(
                    metric,
                    state.values[metric],
                    state.platformsByMetric[metric].orEmpty()
                )
            }

        fun targetValueOf(draft: MetricScheduleDraft): String =
            if (draft.isWeeklyMode) {
                if (draft.dayChecked[todayIndex]) draft.dayTargets[todayIndex] else "0"
            } else {
                draft.dailyTarget
            }

        fun updateSegmentStyle(selected: TextView, unselected: TextView) {
            selected.setBackgroundResource(R.drawable.bg_segmented_thumb)
            selected.elevation = 2f
            selected.setTextColor(Color.parseColor("#5B3FD9"))
            selected.setTypeface(null, Typeface.BOLD)

            unselected.background = null
            unselected.elevation = 0f
            unselected.setTextColor(Color.parseColor("#5E5A6E"))
            unselected.setTypeface(null, Typeface.NORMAL)
        }

        fun renderBulkChipButtons(draft: MetricScheduleDraft) {
            val weekdayAllChecked = (0..4).all { draft.dayChecked[it] }
            val weekendAllChecked = (5..6).all { draft.dayChecked[it] }

            binding.btnApplyWeekday.setBackgroundResource(
                if (weekdayAllChecked) R.drawable.bg_outline_chip_btn_active else R.drawable.bg_outline_chip_btn
            )
            binding.ivCheckWeekday.visibility = if (weekdayAllChecked) View.VISIBLE else View.GONE

            binding.btnApplyWeekend.setBackgroundResource(
                if (weekendAllChecked) R.drawable.bg_outline_chip_btn_active else R.drawable.bg_outline_chip_btn
            )
            binding.ivCheckWeekend.visibility = if (weekendAllChecked) View.VISIBLE else View.GONE
        }

        fun renderScheduleUi(metric: GoalMetric) {
            val draft = draftFor(metric)
            val prevRendering = rendering
            rendering = true

            if (draft.isCombinedScope) {
                updateSegmentStyle(binding.tabScopeCombined, binding.tabScopeIndividual)
                binding.individualAppSegment.visibility = View.GONE
                binding.tvScopeDescription.text = "YouTube · Instagram · KakaoTalk의 사용량을 합산해요."
                binding.tvScheduleCardTitle.text = "합산 목표 · 적용 일정"
            } else {
                updateSegmentStyle(binding.tabScopeIndividual, binding.tabScopeCombined)
                binding.individualAppSegment.visibility = View.VISIBLE
                binding.tvScopeDescription.text = "선택한 앱마다 개별 목표 한도를 설정해요."
                binding.tvScheduleCardTitle.text = "${appNames[draft.selectedAppIndex]} 목표 · 적용 일정"
                appTabs.forEachIndexed { idx, tab ->
                    if (idx == draft.selectedAppIndex) {
                        tab.setBackgroundResource(R.drawable.bg_segmented_thumb)
                        tab.setTextColor(Color.parseColor("#5B3FD9"))
                        tab.setTypeface(null, Typeface.BOLD)
                    } else {
                        tab.background = null
                        tab.setTextColor(Color.parseColor("#5E5A6E"))
                        tab.setTypeface(null, Typeface.NORMAL)
                    }
                }
            }

            if (draft.isWeeklyMode) {
                updateSegmentStyle(binding.tabScheduleWeekly, binding.tabScheduleDaily)
                binding.layoutDailyContainer.visibility = View.GONE
                binding.layoutWeeklyContainer.visibility = View.VISIBLE
            } else {
                updateSegmentStyle(binding.tabScheduleDaily, binding.tabScheduleWeekly)
                binding.layoutWeeklyContainer.visibility = View.GONE
                binding.layoutDailyContainer.visibility = View.VISIBLE
            }

            if (binding.etDailyTarget.text?.toString() != draft.dailyTarget) {
                binding.etDailyTarget.setText(draft.dailyTarget)
            }
            if (binding.etBulkTarget.text?.toString() != draft.bulkTarget) {
                binding.etBulkTarget.setText(draft.bulkTarget)
            }

            dayRows.forEachIndexed { idx, (cb, et, unit) ->
                val checked = draft.dayChecked[idx]
                if (cb.isChecked != checked) cb.isChecked = checked
                et.isEnabled = checked
                et.alpha = if (checked) 1.0f else 0.35f
                unit.alpha = if (checked) 1.0f else 0.35f
                if (et.text?.toString() != draft.dayTargets[idx]) {
                    et.setText(draft.dayTargets[idx])
                }
            }

            if (binding.cbExcludeHolidays.isChecked != draft.excludeHolidays) {
                binding.cbExcludeHolidays.isChecked = draft.excludeHolidays
            }
            binding.tvHolidayDescription.text = if (draft.excludeHolidays) {
                "공휴일에는 목표를 적용하지 않고 쉬어가요."
            } else {
                "선택한 요일의 목표를 공휴일에도 적용해요."
            }

            renderBulkChipButtons(draft)
            rendering = prevRendering
        }

        fun syncMetricToViewModel(metric: GoalMetric) {
            val draft = draftFor(metric)
            val platforms = if (draft.isCombinedScope) {
                appIds.toSet()
            } else {
                setOf(appIds[draft.selectedAppIndex])
            }
            viewModel.onAction(GoalEditorAction.SetPlatforms(metric, platforms))
            viewModel.onAction(GoalEditorAction.UpdateMetricValue(metric, targetValueOf(draft)))
        }

        metricTabs.forEach { (tabView, metric) ->
            tabView.setOnClickListener {
                if (!rendering && viewModel.uiState.value.metric != metric) {
                    viewModel.onAction(GoalEditorAction.EditCard(metric))
                }
            }
        }

        binding.tabScopeCombined.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            draft.isCombinedScope = true
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        binding.tabScopeIndividual.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            draft.isCombinedScope = false
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        appTabs.forEachIndexed { idx, tab ->
            tab.setOnClickListener {
                val metric = viewModel.uiState.value.metric
                val draft = draftFor(metric)
                draft.selectedAppIndex = idx
                renderScheduleUi(metric)
                syncMetricToViewModel(metric)
            }
        }

        binding.tabScheduleDaily.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            draft.isWeeklyMode = false
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        binding.tabScheduleWeekly.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            draft.isWeeklyMode = true
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        binding.etBulkTarget.doAfterTextChanged {
            if (!rendering) {
                val metric = viewModel.uiState.value.metric
                draftFor(metric).bulkTarget = it?.toString().orEmpty()
            }
        }

        binding.btnApplyWeekday.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            val allChecked = (0..4).all { draft.dayChecked[it] }
            if (allChecked) {
                for (i in 0..4) draft.dayChecked[i] = false
            } else {
                val fallback = if (metric == GoalMetric.DAILY_COUNT) "10" else "30"
                val value = binding.etBulkTarget.text?.toString()?.ifBlank { fallback } ?: fallback
                for (i in 0..4) {
                    draft.dayChecked[i] = true
                    draft.dayTargets[i] = value
                }
            }
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        binding.btnApplyWeekend.setOnClickListener {
            val metric = viewModel.uiState.value.metric
            val draft = draftFor(metric)
            val allChecked = (5..6).all { draft.dayChecked[it] }
            if (allChecked) {
                for (i in 5..6) draft.dayChecked[i] = false
            } else {
                val fallback = if (metric == GoalMetric.DAILY_COUNT) "20" else "60"
                val value = binding.etBulkTarget.text?.toString()?.ifBlank { fallback } ?: fallback
                for (i in 5..6) {
                    draft.dayChecked[i] = true
                    draft.dayTargets[i] = value
                }
            }
            renderScheduleUi(metric)
            syncMetricToViewModel(metric)
        }

        binding.etDailyTarget.doAfterTextChanged {
            if (!rendering) {
                val metric = viewModel.uiState.value.metric
                val draft = draftFor(metric)
                draft.dailyTarget = it?.toString().orEmpty()
                if (!draft.isWeeklyMode) syncMetricToViewModel(metric)
            }
        }

        dayRows.forEachIndexed { idx, (cb, et, _) ->
            dayRowContainers[idx].setOnClickListener { cb.toggle() }
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (!rendering) {
                    val metric = viewModel.uiState.value.metric
                    val draft = draftFor(metric)
                    draft.dayChecked[idx] = isChecked
                    renderScheduleUi(metric)
                    if (idx == todayIndex && draft.isWeeklyMode) syncMetricToViewModel(metric)
                }
            }
            et.doAfterTextChanged {
                if (!rendering) {
                    val metric = viewModel.uiState.value.metric
                    val draft = draftFor(metric)
                    draft.dayTargets[idx] = it?.toString().orEmpty()
                    if (idx == todayIndex && draft.isWeeklyMode) syncMetricToViewModel(metric)
                }
            }
        }

        binding.cbExcludeHolidays.setOnCheckedChangeListener { _, isChecked ->
            if (!rendering) {
                val metric = viewModel.uiState.value.metric
                val draft = draftFor(metric)
                draft.excludeHolidays = isChecked
                renderScheduleUi(metric)
            }
        }

        binding.btnBack.setOnClickListener { navigator.back() }

        binding.etContinuousBreak.doAfterTextChanged {
            if (!rendering) viewModel.onAction(GoalEditorAction.SetBreakMinutes(it?.toString().orEmpty()))
        }
        binding.detailTabs.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !rendering) viewModel.onAction(
                GoalEditorAction.EditCard(
                    when (id) {
                        R.id.tab_count -> GoalMetric.DAILY_COUNT
                        R.id.tab_app_time -> GoalMetric.APP_TIME
                        else -> GoalMetric.DAILY_TIME
                    }
                )
            )
        }
        val fields = listOf(
            Triple(binding.metricTime, binding.etTime, GoalMetric.DAILY_TIME),
            Triple(binding.metricCount, binding.etCount, GoalMetric.DAILY_COUNT),
            Triple(binding.metricAppTime, binding.etAppTime, GoalMetric.APP_TIME)
        )
        fields.forEach { (check, input, metric) ->
            check.setOnCheckedChangeListener { _, enabled ->
                if (!rendering) viewModel.onAction(GoalEditorAction.SetMetricEnabled(metric, enabled))
            }
            input.doAfterTextChanged {
                if (!rendering) viewModel.onAction(GoalEditorAction.UpdateMetricValue(metric, it?.toString().orEmpty()))
            }
        }
        listOf(
            binding.detailTime to GoalMetric.DAILY_TIME,
            binding.detailCount to GoalMetric.DAILY_COUNT,
            binding.detailAppTime to GoalMetric.APP_TIME
        ).forEach { (button, metric) ->
            button.setOnClickListener {
                viewModel.onAction(GoalEditorAction.EditCard(metric))
                binding.root.post { binding.root.smoothScrollTo(0, binding.detailPanel.top) }
            }
        }
        listOf(
            binding.cbYoutube to "YOUTUBE",
            binding.cbInstagram to "INSTAGRAM",
            binding.cbKakaotalk to "KAKAOTALK"
        ).forEach { (checkbox, id) ->
            checkbox.setOnCheckedChangeListener { _, _ ->
                if (!rendering) viewModel.onAction(GoalEditorAction.TogglePlatform(id))
            }
        }
        binding.spinnerIntervention.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = GoalIntervention.entries[position]
                if (!rendering && mode != viewModel.uiState.value.intervention) {
                    viewModel.onAction(GoalEditorAction.SelectIntervention(mode))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.btnSave.setOnClickListener {
            val state = viewModel.uiState.value
            val zero = state.selectedMetrics.any { state.values[it]?.toIntOrNull() == 0 }
            if (zero || state.selectedMetrics.any { state.interventionsByMetric[it] == GoalIntervention.RESTRICT }) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("선택한 사용 제한")
                    .setMessage(
                        (if (zero) "0분·0회 목표는 ‘사용하지 않기’입니다. 첫 유효 사용부터 목표에 도달한 것으로 처리합니다.\n\n" else "") +
                                "지금부터 목표를 적용하며 오늘 이미 기록한 사용량은 초기화하지 않습니다. 숏폼 목표는 인식된 숏폼 화면에서만, 앱 시간 목표는 선택한 앱 전체에서 개입합니다. 설정에서 언제든 목표를 중지할 수 있습니다. 이 범위에 동의하고 저장할까요?"
                    )
                    .setNegativeButton("취소", null)
                    .setPositiveButton("동의하고 저장") { _, _ -> saveWithPermission() }
                    .show()
            } else {
                saveWithPermission()
            }
        }
        binding.btnCancel.setOnClickListener { navigator.back() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.saved) {
                        navigator.back()
                        return@collect
                    }

                    if (!state.isLoading && !initializedFromLoadedState) {
                        initializedFromLoadedState = true
                        state.selectedMetrics.forEach { m ->
                            val draft = defaultDraftFor(
                                m,
                                state.values[m],
                                state.platformsByMetric[m].orEmpty()
                            )
                            drafts[m] = draft
                            syncMetricToViewModel(m)
                        }
                        return@collect
                    }

                    rendering = true
                    val enabled = !state.isLoading && !state.isSaving

                    binding.metricSwitcherGroup.isVisible = state.selectedMetrics.size > 1
                    metricTabs.forEach { (tabView, metric) ->
                        tabView.isVisible = metric in state.selectedMetrics
                        if (metric == state.metric) {
                            tabView.setBackgroundResource(R.drawable.bg_segmented_thumb)
                            tabView.setTextColor(Color.parseColor("#5B3FD9"))
                            tabView.setTypeface(null, Typeface.BOLD)
                        } else {
                            tabView.background = null
                            tabView.setTextColor(Color.parseColor("#5E5A6E"))
                            tabView.setTypeface(null, Typeface.NORMAL)
                        }
                    }

                    binding.tvGoalMetricTitle.text = metricLabel(state.metric)
                    val unitText = if (state.metric == GoalMetric.DAILY_COUNT) "회" else "분"
                    binding.tvDailyUnit.text = unitText
                    binding.tvBulkUnit.text = unitText
                    listOf(
                        binding.tvUnitMon, binding.tvUnitTue, binding.tvUnitWed,
                        binding.tvUnitThu, binding.tvUnitFri, binding.tvUnitSat, binding.tvUnitSun
                    ).forEach { it.text = unitText }

                    renderScheduleUi(state.metric)

                    fields.forEach { (check, input, metric) ->
                        check.isChecked = metric in state.selectedMetrics
                        check.isEnabled = enabled && !state.isEditing
                        input.isEnabled = enabled && check.isChecked
                        val text = state.values[metric].orEmpty()
                        if (input.text?.toString() != text) input.setText(text)
                    }
                    listOf(
                        binding.cbYoutube to "YOUTUBE",
                        binding.cbInstagram to "INSTAGRAM",
                        binding.cbKakaotalk to "KAKAOTALK"
                    ).forEach { (check, id) ->
                        check.isChecked = id in state.selectedPlatforms
                        check.isEnabled = enabled
                    }
                    binding.spinnerIntervention.isEnabled = enabled
                    if (binding.spinnerIntervention.selectedItemPosition != state.intervention.ordinal) {
                        binding.spinnerIntervention.setSelection(state.intervention.ordinal)
                    }
                    binding.tvInterventionDescription.text = getString(
                        when (state.intervention) {
                            GoalIntervention.RECORD_ONLY -> R.string.goal_intervention_record_description
                            GoalIntervention.NOTIFY -> R.string.goal_intervention_notify_description
                            GoalIntervention.CONFIRM -> R.string.goal_intervention_confirm_description
                            GoalIntervention.RESTRICT -> R.string.goal_intervention_restrict_description
                        }
                    )
                    binding.tvValidation.text = state.validationMessage.orEmpty()
                    binding.tvValidation.isVisible = !state.validationMessage.isNullOrBlank()
                    binding.tvUnavailable.text = if (state.isLoading) "목표를 불러오는 중…" else state.availabilityMessage
                    if (binding.etContinuousBreak.text?.toString() != state.breakMinutesText) {
                        binding.etContinuousBreak.setText(state.breakMinutesText)
                    }
                    binding.etContinuousBreak.isEnabled = enabled && GoalMetric.DAILY_TIME in state.selectedMetrics
                    renderCards(state)

                    binding.btnSave.isEnabled = state.canSubmit
                    binding.btnSave.backgroundTintList = ColorStateList.valueOf(
                        Color.parseColor(if (state.canSubmit) "#5B3FD9" else "#C7C2D9")
                    )
                    binding.btnSave.text = if (state.isSaving) "저장 중…" else "목표 저장"
                    binding.btnCancel.isEnabled = !state.isSaving
                    rendering = false
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun renderCards(state: GoalEditorUiState) {
        listOf(
            binding.targetTime to GoalMetric.DAILY_TIME,
            binding.targetCount to GoalMetric.DAILY_COUNT,
            binding.targetAppTime to GoalMetric.APP_TIME
        ).forEach { (input, metric) ->
            input.isVisible = metric == state.metric && metric in state.selectedMetrics
        }
        binding.detailTabs.check(
            when (state.metric) {
                GoalMetric.DAILY_TIME -> R.id.tab_time
                GoalMetric.DAILY_COUNT -> R.id.tab_count
                GoalMetric.APP_TIME -> R.id.tab_app_time
            }
        )
        listOf(
            binding.tabTime to GoalMetric.DAILY_TIME,
            binding.tabCount to GoalMetric.DAILY_COUNT,
            binding.tabAppTime to GoalMetric.APP_TIME
        ).forEach { (tab, metric) ->
            tab.isEnabled = !state.isLoading && !state.isSaving && metric in state.selectedMetrics
        }
        binding.tvEditingCard.text = "${metricLabel(state.metric)} · 앱과 개입 방식 설정"
        val cards = listOf(
            Triple(binding.cardTime, binding.summaryTime, binding.averagesTime),
            Triple(binding.cardCount, binding.summaryCount, binding.averagesCount),
            Triple(binding.cardAppTime, binding.summaryAppTime, binding.averagesAppTime)
        )
        GoalMetric.entries.forEachIndexed { index, metric ->
            val (card, summary, averages) = cards[index]
            card.isSelected = metric in state.selectedMetrics
            val platforms = state.platformsByMetric[metric].orEmpty().map(SupportedPlatform::valueOf).toSet()
            val baseline = state.insights?.baseline(metric, platforms)
            val unit = if (metric == GoalMetric.DAILY_COUNT) "회" else "분"
            summary.text = baseline?.let { "%.1f%s / 하루 평균".format(it.averages.values.sum(), unit) }
                ?: "기록 수집 중"
            averages.removeAllViews()
            if (baseline != null) {
                baseline.averages.forEach { (platform, value) ->
                    averages.addView(PrototypeUi.row(requireContext(), platform.displayName, "%.1f%s".format(value, unit)))
                }
            } else {
                averages.addView(PrototypeUi.text(requireContext(), "적용 앱과 유효 3일의 기록이 필요해요.", 12f))
            }
        }
        listOf(
            binding.detailTime to GoalMetric.DAILY_TIME,
            binding.detailCount to GoalMetric.DAILY_COUNT,
            binding.detailAppTime to GoalMetric.APP_TIME
        ).forEach { (button, metric) ->
            button.isEnabled = !state.isLoading && !state.isSaving && metric in state.selectedMetrics
        }
        binding.goalCardDetails.removeAllViews()
        state.selectedMetrics.filter { it == state.metric }.forEach { metric ->
            val platforms = state.platformsByMetric[metric].orEmpty().map { SupportedPlatform.valueOf(it) }.toSet()
            val baseline = state.insights?.baseline(metric, platforms)
            val comparison = PrototypeUi.column(requireContext())
            val unit = if (metric == GoalMetric.DAILY_COUNT) "회" else "분"
            comparison.addView(PrototypeUi.title(requireContext(), "목표 달성 시 내 변화"))
            comparison.addView(
                PrototypeUi.row(
                    requireContext(),
                    "하루 평균",
                    baseline?.let { "%.1f%s".format(it.averages.values.sum(), unit) } ?: "기록 부족"
                )
            )
            comparison.addView(
                PrototypeUi.row(
                    requireContext(),
                    "설정할 하루 한도",
                    state.values[metric].orEmpty().ifEmpty { "—" } + unit
                )
            )
            comparison.addView(PrototypeUi.divider(requireContext()))
            comparison.addView(
                PrototypeUi.text(
                    requireContext(),
                    baseline?.let {
                        "기준 ${it.dates.first()}~${it.dates.last()} · 유효 ${it.dates.size}일\n" +
                                (state.values[metric]?.toIntOrNull()?.let(it::describe) ?: "목표값을 입력해 주세요.")
                    } ?: "유효 3일의 기록이 더 필요해요. 직접 목표를 설정할 수 있어요.",
                    13f,
                    R.color.ui_success
                )
            )
            binding.goalCardDetails.addView(comparison)
            val presets = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            com.example.dopaminecut2.domain.ReductionLevel.entries.forEach { level ->
                val target = baseline?.target(level)
                presets.addView(
                    PrototypeUi.button(requireContext(), "${level.label}\n${level.percent}%") {
                        viewModel.onAction(GoalEditorAction.Recommend(metric, level))
                    }.apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
                            marginEnd = PrototypeUi.dp(requireContext(), 4)
                        }
                        isEnabled = !state.isLoading && !state.isSaving && target != null &&
                                target <= if (metric == GoalMetric.DAILY_COUNT) 999 else 1440
                    }
                )
            }
            binding.goalCardDetails.addView(presets)
        }
    }

    private fun metricLabel(metric: GoalMetric) = when (metric) {
        GoalMetric.DAILY_TIME -> "숏폼 시청 시간"
        GoalMetric.DAILY_COUNT -> "숏폼 시청 영상 수"
        GoalMetric.APP_TIME -> "앱 사용 시간"
    }

    private fun saveWithPermission() {
        if (viewModel.uiState.value.selectedMetrics.any {
                viewModel.uiState.value.interventionsByMetric[it] == GoalIntervention.NOTIFY
            } && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onAction(GoalEditorAction.Save)
        }
    }
}