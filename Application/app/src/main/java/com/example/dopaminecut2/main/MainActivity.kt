package com.example.dopaminecut2.main

import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.ActivityMainBinding
import com.example.dopaminecut2.settings.SettingsFragment
import kotlinx.coroutines.launch


class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    // 뷰모델 연결
    private val viewModel: MainViewModel by viewModels()

    override fun initView() {
        if (supportFragmentManager.findFragmentById(R.id.main_frame_layout) == null) {
            replaceFragment(HomeFragment())
        }

        // 뷰모델에서 유저정보 가져오기
        viewModel.fetchDashboardData()
    }

    override fun setupListeners() {
        setupBottomNavigation()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    replaceFragment(HomeFragment())
                    true
                }
                R.id.nav_stats -> {
                    replaceFragment(StatsFragment())
                    true
                }
                R.id.nav_community -> {
                    replaceFragment(CommunityFragment())
                    true

                }
                R.id.nav_settings -> {
                    replaceFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
    }

    // 여기서 뷰모델 감시 후 화면 글씨 바꿔주기
    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.userNickname.collect { nickname ->
                    // 성공 or 에러 시, 화면 상단에 띄우기
                    binding.tvUserNickname.text = "안녕하세요, ${nickname}님!"
                }
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_frame_layout, fragment)
            .commit()
    }
}