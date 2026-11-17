package com.example.dopaminecut2.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.dopaminecut2.DopamineCutApplication
import com.example.dopaminecut2.R
import com.example.dopaminecut2.auth.AuthActivity
import com.example.dopaminecut2.data.local.OnboardingStage
import com.example.dopaminecut2.databinding.ActivityMainBinding
import com.example.dopaminecut2.di.AppDependencies
import com.example.dopaminecut2.di.ViewModelFactory
import com.example.dopaminecut2.ui.navigation.AppDestination
import com.example.dopaminecut2.ui.navigation.AppNavigator
import com.example.dopaminecut2.ui.navigation.MainTabHeader
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var dependencies: AppDependencies
    private lateinit var viewModel: MainViewModel
    private lateinit var navController: NavController
    private lateinit var tabHeader: MainTabHeader
    private var lastUserId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = (application as DopamineCutApplication).dependenciesOrNull()
        if (resolved == null || resolved.authRepository.currentUserId() == null) {
            redirectToAuthentication()
            return
        }
        dependencies = resolved
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tabHeader = MainTabHeader(binding.tabHeader)

        val navHost = supportFragmentManager.findFragmentById(R.id.main_nav_host)
            as? NavHostFragment ?: NavHostFragment.create(R.navigation.main_nav_graph).also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.main_nav_host, it)
                    .setPrimaryNavigationFragment(it)
                    .commitNow()
            }
        navController = navHost.navController
        binding.bottomNavigation.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, _, _ ->
            updateNavigationChrome()
        }

        viewModel = ViewModelProvider(
            this,
            ViewModelFactory {
                MainViewModel(dependencies.authRepository, dependencies.onboardingStore)
            }
        )[MainViewModel::class.java]
        observeEntry()
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) viewModel.refreshEntry()
        if (::dependencies.isInitialized) dependencies.authRepository.currentUserId()?.let { uid ->
            dependencies.applicationScope.launch { dependencies.goalStore.sync(uid) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::viewModel.isInitialized && ::dependencies.isInitialized) {
            val state = viewModel.entryState.value as? MainEntryState.Ready
            if (state != null && dependencies.authRepository.currentUserId() == state.userId) {
                showEntry(state.userId, state.stage)
            } else viewModel.refreshEntry()
        }
    }

    private fun observeEntry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.entryState.collect { state ->
                    when (state) {
                        MainEntryState.Loading -> showLoading()
                        MainEntryState.AuthenticationRequired -> redirectToAuthentication()
                        is MainEntryState.Error -> {
                            showLoading(false)
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_INDEFINITE)
                                .setAction(R.string.retry) { viewModel.refreshEntry() }
                                .show()
                        }
                        is MainEntryState.Ready -> showEntry(state.userId, state.stage)
                    }
                }
            }
        }
    }

    private fun showEntry(userId: String, stage: OnboardingStage) {
        showLoading(false)
        val accountChanged = lastUserId != null && lastUserId != userId
        lastUserId = userId
        val destination = when (stage) {
            OnboardingStage.INTERVENTION -> AppDestination.InterventionSetup
            OnboardingStage.MEASUREMENT -> AppDestination.MeasurementSetup
            OnboardingStage.COMPLETE -> AppDestination.Home
        }
        val currentId = navController.currentDestination?.id
        val isOnboarding = currentId in ONBOARDING_DESTINATIONS
        val shouldNavigate = accountChanged || when (stage) {
            OnboardingStage.COMPLETE -> isOnboarding
            else -> currentId != destination.destinationId
        }
        if (shouldNavigate) {
            AppNavigator(navController).navigate(destination, clearCurrentFlow = true)
        }
        if (stage == OnboardingStage.COMPLETE && intent.getBooleanExtra("open_goal_settings", false)) {
            intent.removeExtra("open_goal_settings")
            AppNavigator(navController).navigate(AppDestination.Goal)
        }
        if (stage == OnboardingStage.COMPLETE && intent.getStringExtra("habit_owner") == userId) {
            val target = when (intent.getStringExtra("habit_destination")) {
                "goals" -> AppDestination.Goal
                "stats" -> AppDestination.Stats
                "settings" -> AppDestination.Settings
                "change" -> AppDestination.MyChange
                else -> null
            }
            intent.removeExtra("habit_destination"); intent.removeExtra("habit_owner")
            target?.let { AppNavigator(navController).navigate(it) }
        }
    }

    private fun showLoading(show: Boolean = true) {
        binding.entryProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.mainNavHost.visibility = if (show) View.INVISIBLE else View.VISIBLE
        updateNavigationChrome()
    }

    private fun updateNavigationChrome() {
        val contentVisible = binding.entryProgress.visibility != View.VISIBLE &&
            binding.mainNavHost.visibility == View.VISIBLE
        tabHeader.render(navController.currentDestination?.id, contentVisible)
        binding.bottomNavigation.visibility = if (
            contentVisible && navController.currentDestination?.id in TOP_LEVEL_DESTINATIONS
        ) View.VISIBLE else View.GONE
    }

    private fun redirectToAuthentication() {
        startActivity(Intent(this, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private companion object {
        val TOP_LEVEL_DESTINATIONS = setOf(
            R.id.nav_home,
            R.id.nav_goal,
            R.id.nav_stats,
            R.id.nav_settings
        )
        val ONBOARDING_DESTINATIONS = setOf(
            R.id.nav_intervention_setup,
            R.id.nav_goal_candidates,
            R.id.nav_goal_editor,
            R.id.nav_measurement_setup
        )
    }
}
