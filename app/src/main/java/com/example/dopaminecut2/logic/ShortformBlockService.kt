package com.example.dopaminecut2.logic

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import com.example.dopaminecut2.model.DailyRecord
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*
import android.view.Gravity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShortformBlockService : AccessibilityService() {

    /** 지원하는 각 플랫폼별 매니저 목록 */
    private val appManagers = listOf(
        YoutubeManager(),
        InstagramManager(),
        TiktokManager(),
        KakaotalkManager())

    /** 현재 앱별로 누적된 숏폼 시청 횟수를 임시 저장하는 맵 */
    private val currentAppCounts = mutableMapOf<String, Int>()

    /** 사용 시간 측정 대상 앱 목록(패키지명) */
    private val targetPackages = appManagers.map { it.packageName }

    // 1초마다 앱 사용 시간을 올려주는 백그라운드 타이머
    private var usageTimerJob: Job? = null

    /** 현재 화면에 활성화된 앱의 패키지명 */
    private var activePackageName: String? = null

    // 연산 폭주 방지를 위한 캐싱 변수
    private var lastCalculatedVideoId: String? = null
    private var lastVideoIdCalcTime: Long = 0L

    /**
     * 5초 연속 시청을 감지하는 트래커
     * - 화면이 바뀌지 않고 5초(5000ms)가 경과하면 내부 콜백 로직이 실행됨
     */
    private val viewTracker = ViewTracker(viewThresholdMs = 5000L) { _ ->
        activePackageName?.let { pkg ->
            val rootNode = rootInActiveWindow ?: return@let
            val manager = appManagers.find { it.packageName == pkg } ?: return@let

            // 5초 경과 시점(UI 로딩 완료 상태)에 '최종 영상 식별자(채널명+제목)'를 생성
            val finalVideoId = manager.getVideoIdentifier(rootNode)

            if (finalVideoId != null && !finalVideoId.contains("NoDuration")) {
                // 식별자가 유효할 경우 중복 시청 여부를 검사
                if (manager.isDuplicated(finalVideoId)) {
                    Log.d("DopamineCut", "이미 본 영상 : $finalVideoId")
                    return@let // 중복이면 카운트를 올리지 않고 종료
                } else {
                    Log.d("DopamineCut", "[$pkg] 카운트 된 영상: $finalVideoId")
                }
            }

            // 5초 시청이 확인되었고 중복이 아니면 시청 횟수 증가 처리
            processValidView(pkg)
        }
    }
    /**
     * 안드로이드 시스템에서 화면 변화나 사용자 상호작용이 발생할 때마다 호출되는 메인 이벤트 콜백
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val currentTime = System.currentTimeMillis()
        val packageName = event.packageName?.toString() ?: return
        val rootNode = rootInActiveWindow ?: return

        val manager = appManagers.find { it.packageName == packageName } ?: return

        //  홈 화면으로 나갔을 때 타이머가 멈추도록 현재 패키지명 확인
        activePackageName = packageName

        // 500ms(0.5초) 이내의 빈번한 이벤트 발생 시 이전 ID 재사용
        val videoId: String?
        if (currentTime - lastVideoIdCalcTime < 500) {
            videoId = lastCalculatedVideoId
        } else {
            videoId = manager.getVideoIdentifier(rootNode)
            lastCalculatedVideoId = videoId
            lastVideoIdCalcTime = currentTime
        }
        // 현재 화면이 숏폼 섹션인지, 광고인지 판별하는 변수
        val isShortform = manager.isShortformSection(rootNode)
        val isAd = isAdContent(rootNode)

        // 타이머가 중간에 끊기지 않도록, 유튜브의 경우 UI가 변해도 일정한 '채널명'만 Tracking ID로 우선 사용
        val trackingId = if (manager is YoutubeManager) {
            manager.getTrackingId(rootNode)
        } else {
            manager.getVideoIdentifier(rootNode)
        }

        // 광고 화면일 경우 추적 ID를 null로 만들어 카운트를 방지함
        val finalTrackingId = if (isAd) null else trackingId

        // ViewTracker에 현재 상태 전달
        viewTracker.onScreenChanged(
            isShortform = isShortform && !isAd,
            videoId = finalTrackingId,
            isDuplicate = false
        )
    }

    /** UI 노드를 순회하며 Description에 "Ad"(광고)가 포함되어 있는지 확인 */
    private fun isAdContent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (node.contentDescription?.toString() == "Ad") {
            return true
        }

        for (i in 0 until node.childCount) {
            if (isAdContent(node.getChild(i))) return true
        }
        return false
    }

    /** 유효한 시청(5초 경과 및 비중복)으로 판별된 경우 카운트를 올리고, 제한 초과 시 차단함 */
    private fun processValidView(packageName: String) {
        val sharedPref = getSharedPreferences("DopaminePrefs", Context.MODE_PRIVATE)

        // 1. 현재 카운트와 사용자가 설정한 제한 횟수 불러오기
        val limitCount = sharedPref.getInt("limit_count_$packageName", 10)
        val currentCount = sharedPref.getInt("current_count_$packageName", 0)

        // 2. 제한 횟수에 도달했거나 넘었다면 즉시 차단(뒤로가기)
        if (currentCount >= limitCount) {
            // UI 스레드에서 토스트 메시지 띄우기 및 차단
            Handler(Looper.getMainLooper()).post {
                Log.d("DopamineCut", "[$packageName] 숏폼 제한 초과! 차단 실행")
                Toast.makeText(applicationContext, "숏폼 시청 제한 횟수를 초과했습니다.", Toast.LENGTH_SHORT).show()
                performGlobalAction(GLOBAL_ACTION_BACK) // 뒤로가기 실행하여 숏폼 끄기
            }
            return // 카운트 올리지 않고 여기서 함수 종료
        }

        // 3. 아직 제한 횟수가 남았다면 카운트 1 증가
        val newCount = currentCount + 1
        currentAppCounts[packageName] = newCount
        sharedPref.edit().putInt("current_count_$packageName", newCount).apply()

        Log.d("DopamineCut", "[$packageName] ✅ 5초 시청 완료! 현재 카운트: $newCount / $limitCount")
    }

    /** 서비스가 시스템에 의해 중단될 때 타이머 추적 정지 */
    override fun onInterrupt() {
        viewTracker.stopTracking()
    }

    /** 서비스가 완전히 종료될 때 타이머 추적 정지 및 자원 해제 */
    override fun onDestroy() {
        super.onDestroy()
        viewTracker.stopTracking()
    }

    // ==========================================
    // 앱 사용 시간 타이머 로직 시작
    // ==========================================
    override fun onServiceConnected() {
        super.onServiceConnected()
        startUsageTimer()
        Log.d("DopamineCut", "접근성 서비스 연결됨 - 앱 사용 시간 타이머 시작")
    }

    private fun startUsageTimer() {
        usageTimerJob = CoroutineScope(Dispatchers.IO).launch {
            while (coroutineContext.isActive) {
                delay(1000L) // 1초마다 반복

                val currentPkg = activePackageName
                // 현재 화면에 켜져 있는 앱이 타겟 앱 목록에 있다면 1초 증가
                if (currentPkg != null && targetPackages.contains(currentPkg)) {
                    incrementAppUsageTime(currentPkg)
                }
            }
        }
    }

    private fun incrementAppUsageTime(packageName: String) {
        val sharedPref = getSharedPreferences("DopaminePrefs", Context.MODE_PRIVATE)
        val currentTimeSec = sharedPref.getLong("current_time_sec_$packageName", 0L)
        val newTimeSec = currentTimeSec + 1

        // 1초 더한 값 저장
        sharedPref.edit().putLong("current_time_sec_$packageName", newTimeSec).apply()

        // 제한 시간 초과 검사 (분 -> 초 단위로 변환해서 비교)
        val limitTimeMin = sharedPref.getInt("limit_time_$packageName", 30) // 기본값 30분
        if (newTimeSec >= limitTimeMin * 60) {
            // UI 스레드에서 차단 액션 실행
            Handler(Looper.getMainLooper()).post {
                Log.d("DopamineCut", "[$packageName] 제한 시간 초과로 앱 차단")
                Toast.makeText(applicationContext, "앱 사용 제한 시간이 초과되었습니다.", Toast.LENGTH_SHORT).show()
                // 시간 초과 시, 뒤로가기보다 확실하게 '홈 화면'으로 튕겨냅니다.
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

}

    /** ui 읽기용 코드
    ============================================================================================== */
//    private var windowManager: WindowManager? = null
//    private var debugButton: Button? = null
//    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//    }
//
//    override fun onInterrupt() {
//    }
//
//    override fun onServiceConnected() {
//        super.onServiceConnected()
//        showDebugFloatingButton()
//    }
//
//    /**
//     * 화면에 UI 로그 기록 버튼을 생성함.
//     */
//    private fun showDebugFloatingButton() {
//        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
//
//        debugButton = Button(this).apply {
//            text = "LOG"
//            setBackgroundColor(android.graphics.Color.parseColor("#CCFF0000"))
//            setTextColor(android.graphics.Color.WHITE)
//
//            setOnClickListener {
//                executeUiDump() // 로그 기록 실행 함수 호출
//            }
//        }
//
//        val params = WindowManager.LayoutParams(
//            WindowManager.LayoutParams.WRAP_CONTENT,
//            WindowManager.LayoutParams.WRAP_CONTENT,
//            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
//            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
//            PixelFormat.TRANSLUCENT
//        ).apply {
//            gravity = Gravity.TOP or Gravity.START
//            x = 50
//            y = 250
//        }
//
//        windowManager?.addView(debugButton, params)
//    }
//
//    /**
//     * 현재 화면의 노드를 기록함.
//     */
//    private fun executeUiDump() {
//        val rootNode = rootInActiveWindow
//        if (rootNode != null) {
//            Log.d("UI_LOG", "==============================================")
//            Log.d("UI_LOG", "=== UI 기록 ===")
//            dumpAllNodes(rootNode, 0)
//            Log.d("UI_LOG", "==============================================")
//            Toast.makeText(this, "UI 기록됨", Toast.LENGTH_SHORT).show()
//        } else {
//            Toast.makeText(this, "화면을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    /**
//     * 모든 노드를 탐색하여 출력함.
//     */
//    private fun dumpAllNodes(node: AccessibilityNodeInfo?, depth: Int) {
//        if (node == null) return
//
//        val spacer = "  ".repeat(depth)
//        val className = node.className?.toString() ?: "Unknown"
//        val text = node.text?.toString() ?: ""
//        val contentDesc = node.contentDescription?.toString() ?: ""
//        val viewId = node.viewIdResourceName ?: "No-ID"
//
//        Log.d(
//            "UI_LOG",
//            "${spacer}Class: $className | ID: $viewId | Text: $text | Desc: $contentDesc"
//        )
//
//        for (i in 0 until node.childCount) {
//            dumpAllNodes(node.getChild(i), depth + 1)
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        removeDebugFloatingButton()
//    }
//
//    private fun removeDebugFloatingButton() {
//        debugButton?.let {
//            windowManager?.removeView(it)
//            debugButton = null
//        }
//    }
//}
