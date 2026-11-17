package com.example.dopaminecut2.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import com.example.dopaminecut2.ui.common.renderPermissionStatus
import com.example.dopaminecut2.databinding.FragmentSettingsBinding
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.data.local.NotificationOption
import com.example.dopaminecut2.ui.navigation.AppNavigator
import com.example.dopaminecut2.ui.navigation.AppDestination
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

open class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var renderingNotifications = false
    private var tabs: SettingsTabs? = null
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { viewModel.onAction(SettingsAction.Refresh) }
    private val viewModel: SettingsViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        ViewModelFactory {
            SettingsViewModel(
                dependencies.authRepository,
                dependencies.userRepository,
                dependencies.onboardingStore,
                dependencies.measurementPermissionChecker,
                dependencies.notificationSettingsStore, dependencies.habitStore, dependencies.dataControls,
                syncUsage = { uid ->
                    try { dependencies.usageSync.sync(uid, force = true) }
                    finally { if (dependencies.authRepository.currentUserId() == uid) dependencies.usageSyncSchedule.ensure(uid) }
                }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentSettingsBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tabs = SettingsTabs(binding, SettingsSection.fromKey(savedInstanceState?.getString("settings_tab")
            ?: arguments?.getString(AppNavigator.ARG_SETTINGS_SECTION)))
        binding.btnNotificationPermission.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }
        binding.switchMeasurement.setOnCheckedChangeListener { _, enabled ->
            if (!renderingNotifications) viewModel.onAction(SettingsAction.SetAnalysis(enabled, binding.switchContentAnalysis.isChecked))
        }
        binding.switchContentAnalysis.setOnCheckedChangeListener { _, enabled ->
            if (!renderingNotifications) viewModel.onAction(SettingsAction.SetAnalysis(binding.switchMeasurement.isChecked, enabled))
        }
        binding.btnDeleteToday.setOnClickListener { confirmDeletion(false) }
        binding.btnSyncUsage.setOnClickListener { viewModel.onAction(SettingsAction.SyncUsage) }
        binding.btnDeleteAll.setOnClickListener { confirmDeletion(true) }
        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnUsageSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.btnChangeIntervention.setOnClickListener {
            AppNavigator(findNavController()).navigate(AppDestination.InterventionSetup)
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }
        notificationControls().forEach { (control, option) ->
            control.setOnCheckedChangeListener { _, enabled ->
                if (!renderingNotifications) viewModel.onAction(SettingsAction.SetNotification(option, enabled))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(SettingsAction.Refresh)
    }

    private fun render(state: SettingsUiState) {
        binding.progressLoading.isVisible = state.isLoading
        binding.tvNickname.text = state.account?.nickname ?: getString(R.string.loading)
        binding.tvEmail.text = state.account?.email ?: ""
        binding.tvAccessibilityStatus.renderPermissionStatus(state.permissions.accessibilityGranted)
        binding.tvUsageStatus.renderPermissionStatus(state.permissions.usageAccessGranted)
        binding.btnLogout.isEnabled = !state.isLoggingOut
        binding.btnSyncUsage.isEnabled = !state.isSyncingUsage && !state.isChangingData && !state.isLoggingOut
        binding.btnSyncUsage.text = if (state.isSyncingUsage) "동기화 중…" else "사용 기록 동기화"
        renderingNotifications = true
        binding.switchMeasurement.isChecked = state.analysis.measurement
        binding.switchContentAnalysis.isChecked = state.analysis.content
        binding.switchMeasurement.isEnabled = state.analysisLoaded && !state.isChangingData
        binding.switchContentAnalysis.isEnabled = state.analysisLoaded && !state.isChangingData
        binding.btnDeleteToday.isEnabled = state.analysisLoaded && !state.isChangingData
        binding.btnDeleteAll.isEnabled = state.analysisLoaded && !state.isChangingData
        notificationControls().forEach { (control, option) ->
            control.isChecked = state.notifications.isEnabled(option)
            control.isEnabled = state.notificationsLoaded && !state.isSavingNotifications && !state.isLoggingOut
        }
        renderingNotifications = false
        state.message?.let {
            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            viewModel.onAction(SettingsAction.ClearMessage)
        }
    }

    private fun notificationControls(): List<Pair<android.widget.CompoundButton, NotificationOption>> = listOf(
        binding.cbNotificationMorning to NotificationOption.MORNING,
        binding.cbNotificationLunch to NotificationOption.LUNCH,
        binding.cbNotificationEvening to NotificationOption.EVENING,
        binding.switchGoalNotifications to NotificationOption.GOAL_PROGRESS,
        binding.switchMeasurementNotifications to NotificationOption.MEASUREMENT_INTERRUPTION
    )

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.logout) { _, _ -> viewModel.onAction(SettingsAction.Logout) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    private fun confirmDeletion(all: Boolean) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(if (all) "전체 사용 기록 삭제" else "오늘 사용 기록 삭제")
            .setMessage("이 계정의 ${if (all) "전체" else "오늘"} 로컬·Firebase 사용 기록을 삭제합니다. 복구할 수 없습니다. 계정과 목표는 유지되며 기록은 중지됩니다. 다른 기기에서 측정 중이라면 먼저 중지해 주세요.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> viewModel.onAction(SettingsAction.DeleteRecords(all)) }.show()
    }

    override fun onDestroyView() {
        tabs = null
        _binding = null
        super.onDestroyView()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        tabs?.selected?.let { outState.putString("settings_tab", it.key) }
        super.onSaveInstanceState(outState)
    }
}
