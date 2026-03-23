package com.example.dopaminecut2.personal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.dopaminecut2.R
import com.example.dopaminecut2.logic.InstagramManager
import com.example.dopaminecut2.logic.KakaotalkManager
import com.example.dopaminecut2.logic.ShortformSettingActivity
import com.example.dopaminecut2.logic.TiktokManager
import com.example.dopaminecut2.logic.YoutubeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class DopamineSetting : Fragment(R.layout.fragment_dopamine_setting) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    /** 지원하는 각 플랫폼별 매니저 목록 */
    private val appManagers = listOf(
        YoutubeManager(),
        InstagramManager(),
        TiktokManager(),
        KakaotalkManager())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 설정 페이지 이동 버튼 로직
        val shortformBtn = view.findViewById<Button>(R.id.btn_go_to_shortform_setting)
        shortformBtn.setOnClickListener {
            val intent = Intent(requireContext(), ShortformSettingActivity::class.java)
            startActivity(intent)
        }

        // 전체 앱 상태 갱신
        refreshAllStatuses(view)
    }

    override fun onResume() {
        super.onResume()
        // 설정 변경 후 돌아왔을 때나 앱 사용 후 돌아왔을 때 수치 최신화
        view?.let { refreshAllStatuses(it) }
    }

    private fun refreshAllStatuses(view: View) {
        // 앱별 패키지명과 매칭되는 TextView ID 맵핑
        val appMap = mapOf(
            "com.google.android.youtube" to view.findViewById<TextView>(R.id.tv_youtube_status),
            "com.instagram.android" to view.findViewById<TextView>(R.id.tv_instagram_status),
            "com.zhiliaoapp.musically" to view.findViewById<TextView>(R.id.tv_tiktok_status),
            "com.kakao.talk" to view.findViewById<TextView>(R.id.tv_kakaotalk_status)
        )

        // 루프를 돌면서 각 앱의 데이터를 개별적으로 업데이트
        appMap.forEach { (pkgName, textView) ->
            if (textView != null) {
                updateAppSpecificInfo(pkgName, textView)
            }
        }
    }

    private fun updateAppSpecificInfo(packageName: String, textView: TextView) {
        val sharedPref = requireContext().getSharedPreferences("DopaminePrefs", Context.MODE_PRIVATE)

        // 1. 해당 앱(packageName)에 저장된 제한 시간과 현재 시간 가져오기
        val limitTimeMin = sharedPref.getInt("limit_time_$packageName", 30) // 기본값 30분
        val currentTimeSec = sharedPref.getLong("current_time_sec_$packageName", 0L)
        val remainingTimeMin = (limitTimeMin - (currentTimeSec / 60)).coerceAtLeast(0)

        // 2. 해당 앱(packageName)에 저장된 제한 횟수와 현재 횟수 가져오기
        val limitCount = sharedPref.getInt("limit_count_$packageName", 10) // 기본값 10회
        val currentCount = sharedPref.getInt("current_count_$packageName", 0)
        val remainingCount = (limitCount - currentCount).coerceAtLeast(0)

        // 앱 이름 레이블 설정
        val appLabel = appManagers.find { it.packageName == packageName }?.platformName
            ?: if (packageName == "com.kakao.talk") "KakaoTalk" else "Unknown App"

        // 최종적으로 각 TextView에 해당 앱의 데이터가 반영됨
        textView.text = """
        $appLabel
        • 남은 시간: ${remainingTimeMin}분 / ${limitTimeMin}분
        • 숏폼 횟수: ${remainingCount}회 / ${limitCount}회
    """.trimIndent()
    }
}