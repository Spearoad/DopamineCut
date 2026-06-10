package com.example.dopaminecut2.logic

class ViewTracker(
    private val viewThresholdMs: Long = 5000L, // 최소 5초는 봐야 인정됨
    // durationSec(실제 시청 초)를 콜백으로 같이 넘겨주기
    private val onValidViewCounted: (platform: String, videoId: String?, durationSec: Long) -> Unit
) {
    private var currentVideoId: String? = null
    private var currentPlatform: String = ""
    private var startTimeMs: Long = 0L

    fun onScreenChanged(isShortform: Boolean, videoId: String?, isAd: Boolean, platform: String) {
        val normalizedVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() }

        // 숏폼이 아니거나, 광고거나, ID가 없으면 측정 중지 및 계산
        if (!isShortform || normalizedVideoId == null || isAd) {
            stopTrackingAndReport()
            return
        }

        // 보던 영상을 계속 보고 있으면 무시
        if (normalizedVideoId == currentVideoId) return

        // 다른 쇼츠로 넘겼으면, 방금까지 보던 쇼츠 시간 정산.
        stopTrackingAndReport()

        // 쇼츠 새로 측정 시작
        currentVideoId = normalizedVideoId
        currentPlatform = platform
        startTimeMs = System.currentTimeMillis()
    }

    private fun stopTrackingAndReport() {
        if (currentVideoId != null && startTimeMs > 0) {
            val durationSec = (System.currentTimeMillis() - startTimeMs) / 1000

            // 최소 기준(5초) 이상 봤을 때만 기록
            if (durationSec >= viewThresholdMs / 1000) {
                onValidViewCounted(currentPlatform, currentVideoId, durationSec)
            }
        }
        // 초기화
        currentVideoId = null
        startTimeMs = 0L
    }

    fun release() {
        stopTrackingAndReport()
    }
}