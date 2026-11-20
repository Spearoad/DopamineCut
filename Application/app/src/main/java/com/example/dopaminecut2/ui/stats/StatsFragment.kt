package com.example.dopaminecut2.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.FragmentStatsBinding
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.ui.common.ContentState
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

open class StatsFragment : Fragment() {
    private var _binding: FragmentStatsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var rendering = false
    private var dashboard: StatsDashboardRenderer? = null
    private val viewModel: StatsViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        ViewModelFactory {
            StatsViewModel(
                dependencies.userRepository,
                dependencies.authRepository,
                dependencies.dateIdProvider,
                dependencies.goalStore,
                dependencies.habitInsights
            )
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentStatsBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        dashboard = StatsDashboardRenderer(
            binding = binding,
            selectDate = { viewModel.onAction(StatsAction.SelectDate(it)) },
            selectHour = { viewModel.onAction(StatsAction.SelectHour(it)) },
            shiftCalendarMonth = { viewModel.onAction(StatsAction.ShiftCalendarMonth(it)) },
            selectCalendarDate = { viewModel.onAction(StatsAction.SelectCalendarDate(it)) },
            jumpCalendarToday = { viewModel.onAction(StatsAction.JumpCalendarToday) }
        )

        val openPeriodMenu = View.OnClickListener {
            if (!rendering) showPeriodPopupMenu()
        }
        binding.layoutPeriodDropdown.setOnClickListener(openPeriodMenu)
        binding.btnPeriodDropdown.setOnClickListener(openPeriodMenu)

        binding.chipGroupPlatform.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            viewModel.onAction(
                StatsAction.SelectPlatform(
                    when (checkedIds.first()) {
                        R.id.chip_platform_youtube -> SupportedPlatform.YOUTUBE
                        R.id.chip_platform_instagram -> SupportedPlatform.INSTAGRAM
                        R.id.chip_platform_kakao -> SupportedPlatform.KAKAOTALK
                        else -> null
                    }
                )
            )
        }

        binding.metricGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !rendering) {
                viewModel.onAction(
                    StatsAction.SelectMetric(
                        if (id == R.id.btn_metric_count) StatsMetric.COUNT else StatsMetric.TIME
                    )
                )
            }
        }

        binding.tabMainSection.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (rendering) return
                viewModel.onAction(
                    StatsAction.SelectSection(
                        when (tab?.position) {
                            1 -> StatsSection.CONTENT
                            2 -> StatsSection.GOALS
                            else -> StatsSection.USAGE
                        }
                    )
                )
                binding.detailsScroll.scrollTo(0, 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnRetry.setOnClickListener { viewModel.onAction(StatsAction.Retry) }

        @Suppress("UNCHECKED_CAST")
        (childFragmentManager.findFragmentByTag("stats_range") as? MaterialDatePicker<androidx.core.util.Pair<Long, Long>>)?.let { picker ->
            picker.clearOnPositiveButtonClickListeners()
            picker.addOnPositiveButtonClickListener(::submitRange)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: StatsUiState) {
        rendering = true

        binding.btnPeriodDropdown.text = when (state.period) {
            StatsPeriod.TODAY -> "오늘"
            StatsPeriod.SEVEN_DAYS -> "최근 7일"
            StatsPeriod.THIRTY_DAYS -> "최근 30일"
            StatsPeriod.CUSTOM -> "직접 지정"
        }

        val platformChipId = when (state.platform) {
            SupportedPlatform.YOUTUBE -> R.id.chip_platform_youtube
            SupportedPlatform.INSTAGRAM -> R.id.chip_platform_instagram
            SupportedPlatform.KAKAOTALK -> R.id.chip_platform_kakao
            else -> R.id.chip_platform_all
        }
        binding.chipGroupPlatform.check(platformChipId)

        binding.metricGroup.check(if (state.metric == StatsMetric.TIME) R.id.btn_metric_time else R.id.btn_metric_count)

        val tabIndex = when (state.section) {
            StatsSection.USAGE -> 0
            StatsSection.CONTENT -> 1
            StatsSection.GOALS -> 2
        }
        if (binding.tabMainSection.selectedTabPosition != tabIndex) {
            binding.tabMainSection.getTabAt(tabIndex)?.select()
        }

        rendering = false

        val isGoalsTab = state.section == StatsSection.GOALS
        binding.layoutPeriodDropdown.isVisible = !isGoalsTab
        binding.scrollPlatformChips.isVisible = !isGoalsTab
        binding.metricGroup.isVisible = state.section == StatsSection.USAGE

        binding.tvRange.text = if (!isGoalsTab && state.start != null && state.end != null) {
            "${state.start} ~ ${state.end}" + ((state.content as? ContentState.Data)?.value?.let { " · 기록 ${it.recordedDays}/${it.days.size}일" } ?: "")
        } else {
            ""
        }
        binding.progressLoading.isVisible = state.content is ContentState.Loading

        val goalsWithoutUsage = isGoalsTab && state.content is ContentState.Empty
        binding.contentGroup.isVisible = state.content is ContentState.Data || goalsWithoutUsage
        binding.stateGroup.isVisible = state.content !is ContentState.Data && state.content !is ContentState.Loading && !goalsWithoutUsage
        binding.btnRetry.isVisible = state.content is ContentState.Error && state.content.canRetry

        when (val content = state.content) {
            is ContentState.Data -> dashboard?.render(content.value, state)
            is ContentState.Empty -> {
                binding.tvStateMessage.text = content.message
                if (goalsWithoutUsage) {
                    binding.panelUsage.isVisible = false
                    binding.panelContent.isVisible = false
                    binding.panelGoals.isVisible = true
                    dashboard?.renderGoals(state)
                } else {
                    dashboard?.clear()
                }
            }
            is ContentState.Error -> {
                binding.tvStateMessage.text = content.message
                dashboard?.clear()
            }
            is ContentState.Unavailable -> {
                binding.tvStateMessage.text = content.reason
                dashboard?.clear()
            }
            ContentState.Loading -> dashboard?.clear()
        }

        state.message?.let {
            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            viewModel.onAction(StatsAction.ClearMessage)
        }
    }

    private fun showPeriodPopupMenu() {
        val popup = PopupMenu(requireContext(), binding.layoutPeriodDropdown)
        popup.menu.add(0, 0, 0, "오늘")
        popup.menu.add(0, 1, 1, "최근 7일")
        popup.menu.add(0, 2, 2, "최근 30일")
        popup.menu.add(0, 3, 3, "직접 지정")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> viewModel.onAction(StatsAction.SelectPeriod(StatsPeriod.TODAY))
                1 -> viewModel.onAction(StatsAction.SelectPeriod(StatsPeriod.SEVEN_DAYS))
                2 -> viewModel.onAction(StatsAction.SelectPeriod(StatsPeriod.THIRTY_DAYS))
                3 -> chooseRange()
            }
            true
        }
        popup.show()
    }

    private fun chooseRange() {
        if (childFragmentManager.findFragmentByTag("stats_range") != null) return
        val state = viewModel.uiState.value
        val today = LocalDate.parse(state.today, DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.dateRangePicker().setTitleText("통계 기간 · 최대 90일")
            .setCalendarConstraints(CalendarConstraints.Builder().setEnd(today).setValidator(DateValidatorPointBackward.before(today)).build())

        if (state.start != null && state.end != null) {
            picker.setSelection(
                androidx.core.util.Pair(
                    state.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    state.end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                )
            )
        }
        picker.build().apply {
            addOnPositiveButtonClickListener(::submitRange)
        }.show(childFragmentManager, "stats_range")
    }

    private fun submitRange(selection: androidx.core.util.Pair<Long, Long>) {
        val start = selection.first ?: return
        val end = selection.second ?: return
        viewModel.onAction(
            StatsAction.SelectRange(
                Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
            )
        )
    }

    override fun onDestroyView() {
        dashboard?.clear()
        dashboard = null
        _binding = null
        super.onDestroyView()
    }
}