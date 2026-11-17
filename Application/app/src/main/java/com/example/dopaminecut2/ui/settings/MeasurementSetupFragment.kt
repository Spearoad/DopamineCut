package com.example.dopaminecut2.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.ui.common.renderPermissionStatus
import com.example.dopaminecut2.databinding.FragmentMeasurementSetupBinding
import com.example.dopaminecut2.di.ViewModelFactory
import kotlinx.coroutines.launch

class MeasurementSetupFragment : Fragment() {
    private var _binding: FragmentMeasurementSetupBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: SettingsViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        ViewModelFactory {
            SettingsViewModel(
                dependencies.authRepository,
                dependencies.userRepository,
                dependencies.onboardingStore,
                dependencies.measurementPermissionChecker,
                dependencies.notificationSettingsStore
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentMeasurementSetupBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnUsageSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.btnStartMeasurement.setOnClickListener {
            viewModel.onAction(SettingsAction.FinishMeasurementSetup(deferred = false))
        }
        binding.btnLater.setOnClickListener {
            viewModel.onAction(SettingsAction.FinishMeasurementSetup(deferred = true))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvAccessibilityStatus.renderPermissionStatus(state.permissions.accessibilityGranted)
                    binding.tvUsageStatus.renderPermissionStatus(state.permissions.usageAccessGranted)
                    binding.btnStartMeasurement.isEnabled =
                        state.permissions.accessibilityGranted && state.permissions.usageAccessGranted && !state.isSavingMeasurement
                    binding.btnLater.isEnabled = !state.isSavingMeasurement
                    binding.tvMessage.text = state.message.orEmpty()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAction(SettingsAction.Refresh)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
