package com.example.dopaminecut2.ui.goal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.FragmentGoalBinding
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.ui.navigation.AppDestination
import com.example.dopaminecut2.ui.navigation.AppNavigator
import com.example.dopaminecut2.ui.navigation.GoalCandidateSource
import kotlinx.coroutines.launch

class GoalFragment : Fragment() {
    private var _binding: FragmentGoalBinding? = null
    private val selectedMetrics = linkedSetOf(GoalMetric.DAILY_TIME)
    private val binding get() = requireNotNull(_binding)
    private val viewModel: GoalViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        ViewModelFactory {
            GoalViewModel(
                dependencies.authRepository,
                dependencies.onboardingStore,
                dependencies.goalStore, dependencies.habitInsights, dependencies.habitStore
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentGoalBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getStringArrayList("goal_selection")?.let { ids ->
            selectedMetrics.clear()
            selectedMetrics.addAll(ids.mapNotNull { id -> GoalMetric.entries.firstOrNull { it.name == id } })
        }
        val navigator = AppNavigator(findNavController())
        binding.btnGoalPermissions.setOnClickListener {
            findNavController().navigate(R.id.nav_settings, Bundle().apply {
                putString(AppNavigator.ARG_SETTINGS_SECTION, "measurement")
            })
        }
        binding.btnGoalCandidates.setOnClickListener {
            navigator.navigate(AppDestination.GoalCandidates(GoalCandidateSource.PERSONALIZED))
        }
        binding.btnCreateGoal.setOnClickListener {
            navigator.navigate(AppDestination.GoalEditor(com.example.dopaminecut2.ui.navigation.GoalEditorMode.CREATE,
                selectedMetricIds = selectedMetrics.map { it.name }))
        }
        binding.btnMyChange.setOnClickListener { navigator.navigate(AppDestination.MyChange) }
        binding.btnRetry.setOnClickListener { viewModel.onAction(GoalAction.Refresh) }
        binding.btnSyncGoals.setOnClickListener { viewModel.onAction(GoalAction.Sync) }
        binding.btnUseCloudGoals.setOnClickListener { confirmConflict(false) }
        binding.btnUseLocalGoals.setOnClickListener { confirmConflict(true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewModel.load()
    }

    private fun render(state: GoalUiState) {
        renderManagedGoals(state)
        binding.progressLoading.isVisible = state.goalsLoading
        binding.btnRetry.isVisible = state.goalsMessage != null
    }
    private fun renderManagedGoals(state: GoalUiState) {
        val permissions = (requireActivity().application as DopamineCutApplication)
            .requireDependencies().measurementPermissionChecker.currentState()
        val measurement = GoalMeasurementPresentation.from(permissions, null, "")
        binding.tvPermissionNotice.isVisible = measurement.permissionRequired
        binding.tvPermissionNotice.text = "${measurement.missingPermissions} 권한 필요"
        binding.tvGoalSync.text = state.syncStatus.message
        binding.btnUseCloudGoals.isVisible = state.syncStatus.conflict
        binding.btnUseLocalGoals.isVisible = state.syncStatus.conflict
        binding.tvManagedGoalMessage.text = state.goalsMessage ?: if (state.goalsLoading) "목표를 불러오는 중…" else ""
        binding.tvManagedGoalMessage.isVisible = binding.tvManagedGoalMessage.text.isNotBlank()
        binding.managedGoalList.removeAllViews()
        val navigator = AppNavigator(findNavController())
        GoalMetric.entries.forEach { metric ->
            val goal = state.goals.firstOrNull { it.metric == metric }
            val active = goal?.status == com.example.dopaminecut2.domain.GoalStatus.ACTIVE
            val label = when (metric) {
                GoalMetric.DAILY_TIME -> "숏폼 시청 시간"
                GoalMetric.DAILY_COUNT -> "숏폼 시청 영상 수"
                GoalMetric.APP_TIME -> "앱 사용 시간"
            }
            val unit = if (metric == GoalMetric.DAILY_COUNT) "회" else "분"
            val platforms = goal?.platforms ?: com.example.dopaminecut2.domain.SupportedPlatform.entries.toSet()
            val baseline = state.insights?.baseline(metric, platforms)
            val status = if (goal == null) "" else {
                val intervention = when (goal.intervention) {
                    GoalIntervention.RECORD_ONLY -> "기록만 보기"
                    GoalIntervention.NOTIFY -> "알림 받기"
                    GoalIntervention.CONFIRM -> "계속 볼지 확인"
                    GoalIntervention.RESTRICT -> "사용 제한"
                }
                "${if (active) "진행 중" else "중지됨"} · ${goal.target}$unit/일 · $intervention"
            }
            val presentation = GoalMeasurementPresentation.from(permissions,
                baseline?.let { "%.1f%s · 하루 평균".format(it.average, unit) },
                baseline?.averages?.entries?.joinToString(" · ") { (platform, average) ->
                    "${platform.displayName} ${"%.1f%s".format(average, unit)}"
                } ?: "유효 3일 기록 후 추천")
            val card = GoalOverviewCard.create(
                context = requireContext(), title = label,
                average = presentation.primary, platforms = presentation.detail,
                status = status, selected = metric in selectedMetrics,
                editEnabled = !state.goalsLoading && state.changingGoal == null,
                statusAction = if (goal == null) null else if (active) "목표 중지" else "다시 시작",
                statusEnabled = state.changingGoal == null,
                onSelection = { checked ->
                    if (checked) selectedMetrics.add(metric) else selectedMetrics.remove(metric)
                    updateSelectionButton()
                },
                onEdit = {
                    navigator.navigate(AppDestination.GoalEditor(
                        if (goal == null) com.example.dopaminecut2.ui.navigation.GoalEditorMode.CREATE else com.example.dopaminecut2.ui.navigation.GoalEditorMode.EDIT,
                        goalMetricId = goal?.metric?.name, selectedMetricIds = listOf(metric.name)))
                },
                onStatus = {
                    if (goal != null) viewModel.onAction(GoalAction.SetStatus(metric, goal.revision,
                        if (active) com.example.dopaminecut2.domain.GoalStatus.PAUSED else com.example.dopaminecut2.domain.GoalStatus.ACTIVE))
                }, permissionRequired = presentation.permissionRequired
            )
            binding.managedGoalList.addView(card)
        }
        updateSelectionButton()
    }

    private fun updateSelectionButton() {
        binding.btnCreateGoal.text = "선택한 목표 ${selectedMetrics.size}개 설정"
        binding.btnCreateGoal.isEnabled = selectedMetrics.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()
        // Refresh system permission labels without reloading goals or writing settings.
        render(viewModel.uiState.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("goal_selection", ArrayList(selectedMetrics.map { it.name }))
        super.onSaveInstanceState(outState)
    }

    private fun confirmConflict(useLocal: Boolean) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle("사용할 목표 선택")
            .setMessage(if (useLocal) "서버의 목표를 이 기기의 목표로 바꿉니다. 계속할까요?" else "이 기기의 미전송 변경을 서버의 목표로 바꿉니다. 계속할까요?")
            .setNegativeButton("취소", null).setPositiveButton("선택한 설정 사용") { _, _ ->
                viewModel.onAction(GoalAction.ResolveConflict(useLocal))
            }.show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
