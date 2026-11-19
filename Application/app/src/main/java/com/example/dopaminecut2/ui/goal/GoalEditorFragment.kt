package com.example.dopaminecut2.ui.goal

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
    private var rendering = false
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

        setupWeeklyScheduleUi(navigator)

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
                    rendering = true
                    val enabled = !state.isLoading && !state.isSaving

                    binding.tvGoalMetricTitle.text = metricLabel(state.metric)
                    val unitText = if (state.metric == GoalMetric.DAILY_COUNT) "회" else "분"
                    binding.tvDailyUnit.text = unitText
                    binding.tvBulkUnit.text = unitText
                    listOf(
                        binding.tvUnitMon, binding.tvUnitTue, binding.tvUnitWed,
                        binding.tvUnitThu, binding.tvUnitFri, binding.tvUnitSat, binding.tvUnitSun
                    ).forEach { it.text = unitText }

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
                    binding.spinnerIntervention.setSelection(state.intervention.ordinal)
                    binding.tvInterventionDescription.text = getString(
                        when (state.intervention) {
                            GoalIntervention.RECORD_ONLY -> R.string.goal_intervention_record_description
                            GoalIntervention.NOTIFY -> R.string.goal_intervention_notify_description
                            GoalIntervention.CONFIRM -> R.string.goal_intervention_confirm_description
                            GoalIntervention.RESTRICT -> R.string.goal_intervention_restrict_description
                        }
                    )
                    binding.tvValidation.text = state.validationMessage.orEmpty()
                    binding.tvUnavailable.text = if (state.isLoading) "목표를 불러오는 중…" else state.availabilityMessage
                    if (binding.etContinuousBreak.text?.toString() != state.breakMinutesText) {
                        binding.etContinuousBreak.setText(state.breakMinutesText)
                    }
                    binding.etContinuousBreak.isEnabled = enabled && GoalMetric.DAILY_TIME in state.selectedMetrics
                    renderCards(state)
                    binding.btnSave.isEnabled = state.canSubmit
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

    private fun setupWeeklyScheduleUi(navigator: AppNavigator) {
        var isCombinedScope = true
        var isWeeklyMode = true
        var selectedAppIndex = 0
        // 초기 화면 진입 시 월~금 / 토~일이 모두 체크되어 있으므로 둘 다 체크(true) 상태로 시작
        var isWeekdayBulkApplied = true
        var isWeekendBulkApplied = true
        val appNames = listOf("YouTube", "Instagram", "KakaoTalk")

        val appTabs = listOf(
            binding.tabAppYoutube,
            binding.tabAppInstagram,
            binding.tabAppKakaotalk
        )

        val dayRowContainers = listOf(
            binding.rowDayMon,
            binding.rowDayTue,
            binding.rowDayWed,
            binding.rowDayThu,
            binding.rowDayFri,
            binding.rowDaySat,
            binding.rowDaySun
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

        val todayIndex = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6
        }

        fun syncCurrentTargetToViewModel() {
            val activeMetric = viewModel.uiState.value.metric
            val targetStr = if (isWeeklyMode) {
                val (cb, et, _) = dayRows[todayIndex]
                if (cb.isChecked) et.text?.toString().orEmpty() else "0"
            } else {
                binding.etDailyTarget.text?.toString().orEmpty()
            }
            viewModel.onAction(GoalEditorAction.UpdateMetricValue(activeMetric, targetStr))
        }

        fun renderBulkChipButtons() {
            binding.btnApplyWeekday.setBackgroundResource(
                if (isWeekdayBulkApplied) R.drawable.bg_outline_chip_btn_active else R.drawable.bg_outline_chip_btn
            )
            binding.ivCheckWeekday.visibility = if (isWeekdayBulkApplied) View.VISIBLE else View.GONE

            binding.btnApplyWeekend.setBackgroundResource(
                if (isWeekendBulkApplied) R.drawable.bg_outline_chip_btn_active else R.drawable.bg_outline_chip_btn
            )
            binding.ivCheckWeekend.visibility = if (isWeekendBulkApplied) View.VISIBLE else View.GONE
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

        fun updateScopeUI() {
            if (isCombinedScope) {
                updateSegmentStyle(binding.tabScopeCombined, binding.tabScopeIndividual)
                binding.individualAppSegment.visibility = View.GONE
                binding.tvScopeDescription.text = "YouTube · Instagram · KakaoTalk의 사용량을 합산해요."
                binding.tvScheduleCardTitle.text = "합산 목표 · 적용 일정"
            } else {
                updateSegmentStyle(binding.tabScopeIndividual, binding.tabScopeCombined)
                binding.individualAppSegment.visibility = View.VISIBLE
                binding.tvScopeDescription.text = "선택한 앱마다 개별 목표 한도를 설정해요."
                binding.tvScheduleCardTitle.text = "${appNames[selectedAppIndex]} 목표 · 적용 일정"
            }
        }

        fun updateScheduleUI() {
            if (isWeeklyMode) {
                updateSegmentStyle(binding.tabScheduleWeekly, binding.tabScheduleDaily)
                binding.layoutDailyContainer.visibility = View.GONE
                binding.layoutWeeklyContainer.visibility = View.VISIBLE
            } else {
                updateSegmentStyle(binding.tabScheduleDaily, binding.tabScheduleWeekly)
                binding.layoutWeeklyContainer.visibility = View.GONE
                binding.layoutDailyContainer.visibility = View.VISIBLE
            }
            syncCurrentTargetToViewModel()
        }

        binding.tabScopeCombined.setOnClickListener {
            isCombinedScope = true
            updateScopeUI()
        }
        binding.tabScopeIndividual.setOnClickListener {
            isCombinedScope = false
            updateScopeUI()
        }

        appTabs.forEachIndexed { idx, tab ->
            tab.setOnClickListener {
                selectedAppIndex = idx
                appTabs.forEachIndexed { i, t ->
                    if (i == idx) {
                        t.setBackgroundResource(R.drawable.bg_segmented_thumb)
                        t.setTextColor(Color.parseColor("#5B3FD9"))
                        t.setTypeface(null, Typeface.BOLD)
                    } else {
                        t.background = null
                        t.setTextColor(Color.parseColor("#5E5A6E"))
                        t.setTypeface(null, Typeface.NORMAL)
                    }
                }
                binding.tvScheduleCardTitle.text = "${appNames[idx]} 목표 · 적용 일정"
            }
        }

        binding.tabScheduleDaily.setOnClickListener {
            isWeeklyMode = false
            updateScheduleUI()
        }
        binding.tabScheduleWeekly.setOnClickListener {
            isWeeklyMode = true
            updateScheduleUI()
        }

        // 평일 적용 버튼 (적용된 상태에서 한 번 더 누르면 해제)
        binding.btnApplyWeekday.setOnClickListener {
            if (isWeekdayBulkApplied) {
                isWeekdayBulkApplied = false
                for (i in 0..4) {
                    dayRows[i].first.isChecked = false
                }
            } else {
                val value = binding.etBulkTarget.text.toString().ifBlank { "30" }
                for (i in 0..4) {
                    dayRows[i].first.isChecked = true
                    dayRows[i].second.setText(value)
                }
                isWeekdayBulkApplied = true
            }
            renderBulkChipButtons()
            syncCurrentTargetToViewModel()
        }

        // 주말 적용 버튼 (적용된 상태에서 한 번 더 누르면 해제)
        binding.btnApplyWeekend.setOnClickListener {
            if (isWeekendBulkApplied) {
                isWeekendBulkApplied = false
                for (i in 5..6) {
                    dayRows[i].first.isChecked = false
                }
            } else {
                val value = binding.etBulkTarget.text.toString().ifBlank { "60" }
                for (i in 5..6) {
                    dayRows[i].first.isChecked = true
                    dayRows[i].second.setText(value)
                }
                isWeekendBulkApplied = true
            }
            renderBulkChipButtons()
            syncCurrentTargetToViewModel()
        }

        binding.etDailyTarget.doAfterTextChanged {
            if (!isWeeklyMode) syncCurrentTargetToViewModel()
        }
        dayRows.forEachIndexed { idx, (cb, et, unit) ->
            dayRowContainers[idx].setOnClickListener {
                cb.toggle()
            }
            cb.setOnCheckedChangeListener { _, isChecked ->
                et.isEnabled = isChecked
                et.alpha = if (isChecked) 1.0f else 0.35f
                unit.alpha = if (isChecked) 1.0f else 0.35f
                if (idx in 0..4) {
                    isWeekdayBulkApplied = (0..4).all { dayRows[it].first.isChecked }
                } else {
                    isWeekendBulkApplied = (5..6).all { dayRows[it].first.isChecked }
                }
                renderBulkChipButtons()
                if (idx == todayIndex && isWeeklyMode) syncCurrentTargetToViewModel()
            }
            et.doAfterTextChanged {
                if (idx == todayIndex && isWeeklyMode) syncCurrentTargetToViewModel()
            }
        }

        binding.cbExcludeHolidays.setOnCheckedChangeListener { _, isChecked ->
            binding.tvHolidayDescription.text = if (isChecked) {
                "공휴일에는 목표를 적용하지 않고 쉬어가요."
            } else {
                "선택한 요일의 목표를 공휴일에도 적용해요."
            }
        }

        binding.btnBack.setOnClickListener {
            navigator.back()
        }

        // 초기 진입 시 체크 상태(체크됨) 및 목표값 동기화
        renderBulkChipButtons()
        syncCurrentTargetToViewModel()
    }
}