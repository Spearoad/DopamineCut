package com.example.dopaminecut2.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.dopaminecut2.data.local.DataStoreManager
import com.example.dopaminecut2.data.model.DopamineLog
import com.example.dopaminecut2.data.remote.FirebaseDataSource
import com.example.dopaminecut2.data.repository.UserRepository
import com.example.dopaminecut2.logic.ViewTracker
import com.example.dopaminecut2.logic.manager.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AppBlockService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var userRepository: UserRepository
    private lateinit var viewTracker: ViewTracker

    private val appManagers = mapOf(
        "com.google.android.youtube" to YoutubeManager(),
        "com.instagram.android" to InstagramManager(),
        "com.kakao.talk" to KakaotalkManager(),
        "com.zhiliaoapp.musically" to TiktokManager()
    )

    private var currentAppManager: AppManagerInterface? = null

    // 차단을 위해 실시간으로 저장해둘 변수
    private var targetTimeSec = 0L
    private var targetCount = 0L
    private var currentUsedSec = 0L
    private var currentShortformCount = 0L
    private var currentAppStartTime = 0L // 앱 켠 시간 기억하기

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AppBlockService", "접근성 서비스 실행됨.")

        userRepository = UserRepository(FirebaseDataSource(), DataStoreManager(applicationContext))
        observeUserData() // 목표 실시간 감시

        // 5초 시청 달성 시 화면에 알림 띄우기
        // TODO : 확인 필요
        viewTracker = ViewTracker(
            viewThresholdMs = 5000L,
            onValidViewCounted = { platform, videoId, durationSec ->
                Toast.makeText(
                    this@AppBlockService,
                    "숏폼 1회 ($durationSec 초) 기록됨.",
                    Toast.LENGTH_SHORT
                ).show()

                // 실제 본 시간을 전달
                saveDataToFirebase(platform, durationSec)
            }
        )
    }

    // 실시간 데이터 구독 함수
    private fun observeUserData() {
        serviceScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            val todayDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            // 내 목표(시간, 횟수) 가져오기
            launch {
                userRepository.getUserInfoFlow(userId).collect { user ->
                    targetTimeSec = user.targetTimeMin * 60L // 분을 초로 변환
                    targetCount = user.targetCount.toLong()
                }
            }

            // 오늘 하루 전체 앱 사용량 합산하기
            launch {
                userRepository.getDailyStatisticsFlow(userId, todayDate).collect { stats ->
                    if (stats != null) {
                        var totalSec = 0L
                        var totalCount = 0L
                        stats.appUsage.values.forEach { usage ->
                            totalSec += usage.runTimeSec
                            totalCount += usage.shortformCount
                        }
                        currentUsedSec = totalSec
                        currentShortformCount = totalCount
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val newAppManager = appManagers[packageName]

        if (newAppManager != currentAppManager) {
            if (currentAppManager != null && currentAppStartTime > 0) {
                val totalAppUsedSec = (System.currentTimeMillis() - currentAppStartTime) / 1000
                if (totalAppUsedSec > 0) {
                    saveGeneralTimeToFirebase(currentAppManager!!.platformName, totalAppUsedSec)
                }
            }

            currentAppManager = newAppManager
            viewTracker.onScreenChanged(false, null, false, "")

            // 새로 켠 앱의 타이머 시작
            if (currentAppManager != null) {
                currentAppStartTime = System.currentTimeMillis()
            }
        }

        if (currentAppManager == null) return

        // 차단 로직 : 목표를 초과했는지 검사
        if (isTargetExceeded()) {
            executeAppBlock()
            return
        }

        val rootNode = rootInActiveWindow ?: return

        val isShortform = currentAppManager!!.isShortformSection(rootNode)
        val isAd = currentAppManager!!.isAdContent(rootNode)
        val videoId = currentAppManager!!.getVideoIdentifier(rootNode)

        viewTracker.onScreenChanged(
            isShortform = isShortform,
            videoId = videoId,
            isAd = isAd,
            platform = currentAppManager!!.platformName
        )
    }

    // 차단 조건 검사 함수
    private fun isTargetExceeded(): Boolean {
        // 목표가 0이면 아직 설정 안한 것이므로 차단 X
        if (targetTimeSec == 0L || targetCount == 0L) return false

        // 사용 시간이 목표를 넘었거나 or 숏폼 횟수가 목표를 넘었으면 true(차단)
        return (currentUsedSec >= targetTimeSec) || (currentShortformCount >= targetCount)
    }

    // TODO : 일반 목적으로 인해 튕기기로 함. 토론 후 다른 대안 필요할 수 있음.
    // 차단 실행 (홈 화면으로 튕기기)
    private fun executeAppBlock() {
        Toast.makeText(this, "도파민 목표 초과 : 앱이 차단되었습니다.", Toast.LENGTH_SHORT).show()
        performGlobalAction(GLOBAL_ACTION_HOME) // 홈 화면 으로 강제 이동
    }

    private fun saveDataToFirebase(platform: String, durationSec: Long) {
        serviceScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            val todayDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            try {
                userRepository.incrementAppUsage(
                    userId = userId,
                    date = todayDate,
                    platform = platform.lowercase(),
                    runTimeSec = durationSec,  // 실제 본 시간 넣기
                    shortformCount = 1L
                )

                val log = DopamineLog(
                    userId = userId,
                    platform = platform,
                    category = "UNKNOWN",
                    durationSec = durationSec
                )
                userRepository.addDopamineLog(log)
            } catch (e: Exception) {
                Log.e("AppBlockService", "Firebase 저장 실패", e)
            }
        }
    }

    // 총 사용 시간만 파이어베이스에 올리는 함수
    private fun saveGeneralTimeToFirebase(platform: String, totalUsedSec: Long) {
        serviceScope.launch {
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            val todayDate = SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())

            val documentId = "${userId}_${todayDate}"
            val updates = hashMapOf<String, Any>(
                "user_id" to userId,
                "date" to todayDate,
                "app_usage" to hashMapOf(
                    platform.lowercase() to hashMapOf(
                        // 숏폼 관련은 제외, 총 사용 시간만 누적
                        "run_time_sec" to FieldValue.increment(totalUsedSec)
                    )
                )
            )
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("daily_statistics").document(documentId)
                .set(updates, com.google.firebase.firestore.SetOptions.merge())
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        viewTracker.release()
        serviceScope.cancel()
    }
}