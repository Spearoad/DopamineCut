package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import java.util.Locale

/** KakaoTalk signals are text based until target-device accessibility IDs are recorded. */
class KakaotalkEntryDetector {
    private val markers = listOf("숏폼", "shorts", "shortform", "쇼츠")
    private val actions = listOf("좋아요", "like", "댓글", "comment", "공유", "share", "리믹스", "remix", "팔로우", "follow")
    private val overlayIds = setOf(
        "comment_sheet", "comment_bottom_sheet", "share_sheet", "share_bottom_sheet",
        "comments_container", "bottom_sheet_container", "bottom_sheet_container_view"
    )

    fun detect(snapshot: ScreenSnapshot): ShortformScreenDetection {
        val hasMarker = snapshot.containsAny(markers)
        val hasAction = snapshot.containsAny(actions)
        val overlayEvidence = snapshot.elements.any { element ->
            val id = element.viewId.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
            val className = element.className.orEmpty()
            id in overlayIds || className.endsWith("Dialog") || className.endsWith("BottomSheet")
        }
        val evidence = buildSet {
            if (hasMarker) add("kakaotalk_shortform_marker")
            if (hasAction) add("kakaotalk_video_action")
            if (overlayEvidence) add("kakaotalk_overlay_structure")
        }

        // An overlay is meaningful only to the stateful coordinator when a video is active.
        if (overlayEvidence) return ShortformScreenDetection(ShortformScreenState.OVERLAY, .75f, evidence)
        if (hasMarker && hasAction) return ShortformScreenDetection(ShortformScreenState.INSIDE, .65f, evidence)
        if (hasMarker) return ShortformScreenDetection(ShortformScreenState.CANDIDATE, .45f, evidence)
        return ShortformScreenDetection(ShortformScreenState.OUTSIDE, .80f, evidence)
    }
}

data class KakaotalkTrackingDecision(
    val retain: Boolean,
    val paused: Boolean,
    val contentKey: String?,
    val switched: Boolean = false,
    val reason: String,
    val candidateKey: String? = null
)

/** Debounces entry and video changes; uses elapsed time supplied by Android's monotonic clock. */
class KakaotalkTrackingCoordinator(
    private val settleMs: Long = 300L,
    private val identityGraceMs: Long = 1_500L
) {
    private val detector = KakaotalkEntryDetector()
    private var activeKey: String? = null
    private var pendingKey: String? = null
    private var pendingSinceMs = 0L
    private var lastReliableMs: Long? = null
    private var lastNowMs: Long? = null

    init {
        require(settleMs > 0 && identityGraceMs >= settleMs)
    }

    fun detect(snapshot: ScreenSnapshot): ShortformScreenDetection = detector.detect(snapshot)

    fun observe(snapshot: ScreenSnapshot, contentKey: String?, explicitAd: Boolean, nowMs: Long): KakaotalkTrackingDecision {
        require(nowMs >= 0L)
        if (lastNowMs?.let { nowMs < it } == true) reset()
        lastNowMs = nowMs
        val detection = detector.detect(snapshot)

        if (explicitAd) {
            reset()
            return KakaotalkTrackingDecision(false, true, null, reason = "EXPLICIT_AD")
        }

        val currentKey = contentKey?.trim()?.takeIf(String::isNotEmpty)
        if (detection.state == ShortformScreenState.OVERLAY && activeKey != null) {
            pendingKey = null
            lastReliableMs = nowMs
            return KakaotalkTrackingDecision(true, true, activeKey, reason = "PLAYER_OVERLAY")
        }

        if (detection.state != ShortformScreenState.INSIDE || currentKey == null) {
            pendingKey = null
            val withinGrace = lastReliableMs?.let { nowMs - it <= identityGraceMs } == true
            if (activeKey != null && withinGrace) {
                val reason = if (detection.state == ShortformScreenState.OUTSIDE) "SCREEN_EVIDENCE_GAP" else "IDENTITY_GAP"
                return KakaotalkTrackingDecision(true, true, activeKey, reason = reason)
            }
            reset()
            val reason = if (detection.state == ShortformScreenState.OUTSIDE) "LEFT_SHORTFORM" else "NOT_CONFIRMED_OR_NO_IDENTITY"
            return KakaotalkTrackingDecision(false, true, null, reason = reason)
        }

        if (currentKey == activeKey) {
            pendingKey = null
            lastReliableMs = nowMs
            return KakaotalkTrackingDecision(true, false, activeKey, reason = "SAME_VIDEO")
        }

        if (pendingKey != currentKey) {
            pendingKey = currentKey
            pendingSinceMs = nowMs
            lastReliableMs = nowMs
            return KakaotalkTrackingDecision(
                retain = activeKey != null,
                paused = true,
                contentKey = activeKey,
                reason = "VIDEO_CHANGE_PENDING",
                candidateKey = currentKey.takeIf { activeKey == null }
            )
        }

        lastReliableMs = nowMs
        if (nowMs - pendingSinceMs < settleMs) {
            return KakaotalkTrackingDecision(activeKey != null, true, activeKey, reason = "VIDEO_CHANGE_SETTLING")
        }

        val changed = activeKey != null
        activeKey = currentKey
        pendingKey = null
        return KakaotalkTrackingDecision(true, false, activeKey, switched = changed, reason = "STABLE_VIDEO")
    }

    fun reset() {
        activeKey = null
        pendingKey = null
        pendingSinceMs = 0L
        lastReliableMs = null
        lastNowMs = null
    }
}
