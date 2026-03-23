package com.example.dopaminecut2.logic

import kotlinx.coroutines.*
import android.util.Log

/** * 숏폼 영상을 사용자가 실제로 유효하게 시청했는지(설정된 시간 이상 머물렀는지) 추적하고 판별하는 타이머 클래스
 * @param viewThresholdMs 유효 시청으로 인정할 기준 대기 시간 (예: 5000ms = 5초)
 * @param onValidViewCounted 목표 시청 시간을 무사히 달성했을 때 카운트를 증가시키기 위해 실행되는 콜백 함수
 */
class ViewTracker(
    private val viewThresholdMs: Long = 5000L, // 기본 유효 시청 판단 시간 (3초)
    private val onValidViewCounted: (String) -> Unit // 3초 달성 시 실행될 콜백 (카운트 증가용)
) {
    /** 백그라운드에서 목표 시간 동안 대기하는 타이머 작업 객체 */
    private var trackingJob: Job? = null

    /** 현재 타이머가 작동 중인 영상의 고유 식별자 */
    private var currentVideoId: String? = null

    /** * 접근성 서비스에서 화면 노드가 변경될 때마다 호출하여 타이머의 시작/유지/정지를 결정하는 핵심 제어 함수
     * @param isShortform 현재 화면이 숏폼 섹션인지 여부
     * @param videoId 현재 화면에서 추출한 영상 식별자 (광고면 null)
     * @param isDuplicate 이미 시청하여 카운트가 올라간 중복 영상인지 여부
     */
    fun onScreenChanged(isShortform: Boolean, videoId: String?, isDuplicate: Boolean) {
        // 1. 숏폼 화면을 벗어났거나 식별자가 없는 경우(광고 등) -> 즉시 타이머 중지
        if (!isShortform || videoId == null) {
            stopTracking()
            return
        }

        // 2. 현재 이미 타이머가 돌고 있는 '같은 영상'이라면 무시 -> 타이머 계속 유지
        if (videoId == currentVideoId) {
            return
        }

        // 3. 화면이 바뀌어서 '다른 영상'이 들어왔으므로 기존 타이머 강제 종료 및 초기화
        stopTracking()

        // 4. 이미 카운트가 올라갔던 '중복 시청 영상'이라면 새 타이머가 시작하지 않음
        if (isDuplicate) {
            Log.d("DopamineCut", "[ViewTracker] 중복 영상 패스: $videoId")
            return
        }

        // 5. 모든 관문을 통과한 '완전 새로운 영상' ->  새 타이머 시작함
        startTracking(videoId)
    }

    /** * 백그라운드 코루틴을 생성하여 지정된 시간(viewThresholdMs) 동안 대기하는 타이머를 작동시킴
     */
    private fun startTracking(videoId: String) {
        currentVideoId = videoId
        Log.d("DopamineCut", "[ViewTracker] 새 숏폼 인식됨: $videoId")

        // 백그라운드에서 시간을 카운트 함
        trackingJob = CoroutineScope(Dispatchers.Default).launch {
            delay(viewThresholdMs) // 설정된 시간(예: 5초) 동안 대기

            // 5초 동안 취소되지 않았다면 유효 시청으로 인정함
            withContext(Dispatchers.Main) {
                Log.d("DopamineCut", "[ViewTracker] 5초 달성, 카운트 증가 요청: $videoId")
                onValidViewCounted(videoId) // 카운트 증가 콜백 실행
            }

            trackingJob = null
        }
    }

    /** 3초가 되기 전에 스크롤을 넘기거나 앱을 껐을 때 호출되어 스톱워치를 멈춤 */
    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.d("DopamineCut", "[ViewTracker] 3초 미만 시청으로 카운트 취소됨")
            trackingJob?.cancel() // 진행 중인 타이머를 즉시 취소
        }
        trackingJob = null
        currentVideoId = null
    }
}