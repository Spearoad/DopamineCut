package com.example.dopaminecut2.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.ActivityMainBinding
import com.example.dopaminecut2.settings.SettingsFragment
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (auth.currentUser == null) {
            finish()
            return
        }

        loadUserNickname()
        setupTabs()
        setupFab()

        if (savedInstanceState == null) {
            replaceFragment(HomeFragment())
        }
    }

    private fun loadUserNickname() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val snapshot = firestore.collection("users").document(uid).get().await()
                val nickname = snapshot.getString("nickname")
                binding.tvUserNickname.text = nickname ?: auth.currentUser?.email ?: "사용자"
            } catch (_: Exception) {
                binding.tvUserNickname.text = auth.currentUser?.email ?: "사용자"
            }
        }
    }

    private fun setupTabs() {
        binding.mainTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> replaceFragment(HomeFragment())
                    1 -> replaceFragment(CommunityFragment())
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupFab() {
        binding.fabUserSettings.setOnClickListener {
            replaceFragment(SettingsFragment())
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_frame_layout, fragment)
            .commit()
    }
}
