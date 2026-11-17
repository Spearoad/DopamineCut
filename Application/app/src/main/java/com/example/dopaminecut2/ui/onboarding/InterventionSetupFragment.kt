package com.example.dopaminecut2.ui.onboarding

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
import com.example.dopaminecut2.data.local.OnboardingStage
import com.example.dopaminecut2.databinding.FragmentInterventionSetupBinding
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.domain.InterventionMode
import com.example.dopaminecut2.ui.navigation.AppDestination
import com.example.dopaminecut2.ui.navigation.AppNavigator
import kotlinx.coroutines.launch

class InterventionSetupFragment : Fragment() {
    private var currentBinding: FragmentInterventionSetupBinding? = null
    private val binding get() = requireNotNull(currentBinding)
    private var rendering = false
    private val viewModel: InterventionSetupViewModel by viewModels {
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        ViewModelFactory { InterventionSetupViewModel(dependencies.authRepository, dependencies.onboardingStore) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentInterventionSetupBinding.inflate(inflater, container, false).also { currentBinding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val savedMode = savedInstanceState?.getString("intervention_mode")?.let { id ->
            InterventionMode.entries.firstOrNull { it.name == id }
        }
        viewModel.restoreDraft(savedInstanceState?.getString("intervention_owner"), savedMode)
        val choices = listOf(Triple(binding.modeRecord, binding.cardRecord, InterventionMode.RECORD_ONLY),
            Triple(binding.modeNotify, binding.cardNotify, InterventionMode.NOTIFY),
            Triple(binding.modeConfirm, binding.cardConfirm, InterventionMode.CONFIRM),
            Triple(binding.modeRestrict, binding.cardRestrict, InterventionMode.RESTRICT))
        choices.forEach { (radio, card, mode) ->
            radio.setOnCheckedChangeListener { _, checked ->
                if (!rendering && checked) viewModel.select(mode)
            }
            card.setOnClickListener { if (radio.isEnabled) viewModel.select(mode) }
        }
        binding.btnContinue.setOnClickListener { viewModel.save() }
        binding.btnRecordOnly.setOnClickListener { viewModel.save(recordOnly = true) }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    rendering = true
                    binding.progressLoading.isVisible = state.loading || state.saving
                    binding.tvSetupTitle.setText(if (state.editing) R.string.intervention_default_heading else R.string.intervention_choose_heading)
                    binding.tvSetupIntro.setText(if (state.editing) R.string.intervention_default_intro else R.string.intervention_choose_intro)
                    binding.tvScopeNotice.setText(if (state.editing) R.string.intervention_existing_goal_notice else R.string.intervention_before_goal_notice)
                    choices.forEach { (radio, card, mode) ->
                        radio.isChecked = state.selection == mode
                        card.isSelected = state.selection == mode
                    }
                    listOf(binding.modeRecord, binding.modeNotify, binding.modeConfirm, binding.modeRestrict).forEach {
                        it.isEnabled = state.loaded && !state.saving
                    }
                    binding.btnContinue.isEnabled = state.loaded && !state.saving && state.selection != null
                    binding.btnContinue.setText(if (state.editing) R.string.save else R.string.intervention_start_action)
                    binding.btnRecordOnly.isEnabled = state.loaded && !state.saving
                    binding.btnRecordOnly.isVisible = !state.editing
                    binding.btnRetry.isVisible = !state.loading && !state.loaded && state.message != null
                    binding.tvMessage.text = state.message.orEmpty()
                    binding.tvMessage.isVisible = !state.message.isNullOrBlank()
                    rendering = false
                    state.nextStage?.let { next ->
                        val navigator = AppNavigator(findNavController())
                        if (next == OnboardingStage.COMPLETE) {
                            if (findNavController().currentDestination?.id == R.id.nav_intervention_setup) navigator.back()
                        } else if (findNavController().currentDestination?.id == R.id.nav_intervention_setup) {
                            navigator.navigate(AppDestination.MeasurementSetup, clearCurrentFlow = true)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("intervention_mode", viewModel.uiState.value.selection?.name)
        val dependencies = (requireActivity().application as DopamineCutApplication).requireDependencies()
        outState.putString("intervention_owner", dependencies.authRepository.currentUserId())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        currentBinding = null
        super.onDestroyView()
    }
}
