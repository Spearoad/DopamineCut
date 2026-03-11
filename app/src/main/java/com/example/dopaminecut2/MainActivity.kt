package com.example.dopaminecut2

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.dopaminecut2.auth.LoginActivity
import com.example.dopaminecut2.community.CommunityFragment
import com.example.dopaminecut2.logic.ShortformSettingActivity
import com.example.dopaminecut2.personal.DopamineSetting
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = Intent(this, ShortformSettingActivity::class.java)
        startActivity(intent)

//        val currentUser = auth.currentUser
//
//        if (currentUser == null) {
//            // 로그인이 안 되어 있으면 로그인 화면으로 이동
//            val intent = Intent(this, LoginActivity::class.java)
//            startActivity(intent)
//            finish()
//        } else {
//            // 1. 로그인 성공 시 사용자 닉네임 불러와서 상단에 표시
//            loadUserProfile(currentUser.uid)
//
//            // 2. 탭 레이아웃 설정 및 초기 화면(DopamineSetting) 진입
//            setupTabs()
//
//            // 3. 하단 유저 설정 버튼(FAB) 연결
//            val fabSettings = findViewById<FloatingActionButton>(R.id.fab_user_settings)
//            fabSettings.setOnClickListener {
//                showSettingsDialog()
//            }
//        }
    }

    // 사용자 설정 다이얼로그 (알림 설정 / 로그아웃)
    private fun showSettingsDialog() {
        val options = arrayOf("알림 설정", "로그아웃", "취소")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("사용자 설정")
        builder.setItems(options) { dialog, which ->
            when (which) {
                0 -> {
                    // 알림 설정 로직 (추후 개인화팀에서 구현 예정)
                    Toast.makeText(this, "알림 설정으로 이동합니다.", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    // 로그아웃 로직
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            }
            dialog.dismiss()
        }
        builder.show()
    }

    // Firestore에서 닉네임 로드
    private fun loadUserProfile(uid: String) {
        val nicknameTv = findViewById<TextView>(R.id.tv_user_nickname)
        db.collection("Users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val nickname = document.getString("nickname")
                    nicknameTv.text = "${nickname}님, 오늘도 도파민 컷!"
                }
            }
            .addOnFailureListener {
                nicknameTv.text = "사용자 정보를 불러오지 못했습니다."
            }
    }

    // 탭 레이아웃 설정 (도파민 설정 / 커뮤니티)
    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.main_tab_layout)

        // 앱 시작 시 기본 화면으로 DopamineSetting(개인화 영역) 표시
        replaceFragment(DopamineSetting())

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> replaceFragment(DopamineSetting()) // 첫 번째 탭: 도파민 설정
                    1 -> replaceFragment(CommunityFragment()) // 두 번째 탭: 커뮤니티
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // 프래그먼트 교체 함수
    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_frame_layout, fragment)
            .commit()
    }
}