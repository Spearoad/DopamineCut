package com.example.dopaminecut2.logic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.dopaminecut2.R
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import android.content.Context

class ShortformSettingActivity : AppCompatActivity() {

    // 💡 각 앱의 패키지명과 xml 파일(UI)의 EditText ID를 매핑해주는 데이터 클래스
    private data class AppSettingUI(
        val packageName: String,
        val timeEditId: Int,
        val countEditId: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortform_setting)

        val sharedPref = getSharedPreferences("DopaminePrefs", Context.MODE_PRIVATE)

        // 1. 관리할 앱 목록과 연결할 UI(EditText) ID 리스트
        val appSettings = listOf(
            AppSettingUI(YoutubeManager().packageName, R.id.et_youtube_time_limit, R.id.et_youtube_count_limit),
            AppSettingUI(InstagramManager().packageName, R.id.et_instagram_time_limit, R.id.et_instagram_count_limit),
            AppSettingUI(TiktokManager().packageName, R.id.et_tiktok_time_limit, R.id.et_tiktok_count_limit),
            AppSettingUI(KakaotalkManager().packageName, R.id.et_kakaotalk_time_limit, R.id.et_kakaotalk_count_limit)
        )

        // 나중에 저장 버튼을 눌렀을 때 값을 읽어오기 위해 뷰들을 담아둘 Map
        val timeInputViews = mutableMapOf<String, EditText>()
        val countInputViews = mutableMapOf<String, EditText>()

        // 2. 기존 설정 불러오기 및 뷰 연결 (반복문으로 한 번에 처리!)
        for (app in appSettings) {
            val timeInput = findViewById<EditText>(app.timeEditId)
            val countInput = findViewById<EditText>(app.countEditId)

            timeInputViews[app.packageName] = timeInput
            countInputViews[app.packageName] = countInput

            // 저장된 값이 없으면 기본값(시간: 30분, 횟수: 10회) 표시
            timeInput.setText(sharedPref.getInt("limit_time_${app.packageName}", 30).toString())
            countInput.setText(sharedPref.getInt("limit_count_${app.packageName}", 10).toString())
        }

        // 3. 저장 버튼 클릭 시 모든 앱의 설정값을 한 번에 저장
        val btnSave = findViewById<Button>(R.id.btn_save_settings)
        btnSave.setOnClickListener {
            with(sharedPref.edit()) {
                for (app in appSettings) {
                    // Map에서 각 앱의 EditText 뷰를 꺼내서 사용자가 입력한 값을 가져옴
                    val timeInput = timeInputViews[app.packageName]
                    val countInput = countInputViews[app.packageName]

                    // 빈칸이거나 숫자가 아니면 기본값(30, 10)으로 안전하게 처리
                    val timeLimit = timeInput?.text?.toString()?.toIntOrNull() ?: 30
                    val countLimit = countInput?.text?.toString()?.toIntOrNull() ?: 10

                    // 패키지명을 키값으로 저장
                    putInt("limit_time_${app.packageName}", timeLimit)
                    putInt("limit_count_${app.packageName}", countLimit)
                }
                apply() // 한 번에 디스크에 쓰기
            }
            Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            finish() // 설정 창 닫기
        }
    }
}