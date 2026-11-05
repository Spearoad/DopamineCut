package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.NormalizedBounds
import com.example.dopaminecut2.logic.manager.ScreenElement
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaotalkTrackingTest {
    @Test
    fun `entry is confirmed only after the same identified video settles`() {
        val manager = KakaotalkManager()
        val video = video("고양이 영상", "좋아요 1.2만")

        assertEquals(ShortformScreenState.OUTSIDE,
            manager.detectShortformScreen(snapshot("펑", "좋아요", "친구 영상")).state)
        val candidate = manager.observeTracking(video, 0L)
        assertFalse(candidate.retain)
        assertEquals(manager.getVideoIdentifier(video), candidate.candidateKey)
        assertFalse(manager.observeTracking(video, 299L).retain)
        val entered = manager.observeTracking(video, 300L)

        assertTrue(entered.retain)
        assertFalse(entered.paused)
        assertEquals("STABLE_VIDEO", entered.reason)
        assertNotNull(entered.contentKey)
    }

    @Test
    fun `counter and playback changes keep the same video identity`() {
        val manager = KakaotalkManager()
        val first = manager.getVideoIdentifier(video("고양이 영상", "좋아요 1.2만", "00:12"))
        val updated = manager.getVideoIdentifier(video("고양이 영상", "좋아요 1.3만", "00:18"))
        val next = manager.getVideoIdentifier(video("여행 영상", "좋아요 1.3만", "00:03"))

        assertNotNull(first)
        assertEquals(first, updated)
        assertTrue(first != next)
        assertNull(manager.getVideoIdentifier(snapshot("펑", "좋아요", "공유", "00:12", "댓글 32개")))
    }

    @Test
    fun `rapid transient video change does not switch the active session`() {
        val manager = KakaotalkManager()
        val firstVideo = video("첫 영상")
        val nextVideo = video("다음 영상")
        manager.observeTracking(firstVideo, 0L)
        manager.observeTracking(firstVideo, 300L)

        val firstFrame = manager.observeTracking(nextVideo, 400L)
        val bounceBack = manager.observeTracking(firstVideo, 550L)

        assertTrue(firstFrame.retain)
        assertTrue(firstFrame.paused)
        assertEquals("VIDEO_CHANGE_PENDING", firstFrame.reason)
        assertEquals("SAME_VIDEO", bounceBack.reason)
        assertFalse(bounceBack.paused)
        assertEquals(manager.getVideoIdentifier(firstVideo), bounceBack.contentKey)
    }

    @Test
    fun `stable next video switches only after settling`() {
        val manager = KakaotalkManager()
        val firstVideo = video("첫 영상")
        val nextVideo = video("다음 영상")
        manager.observeTracking(firstVideo, 0L)
        manager.observeTracking(firstVideo, 300L)

        manager.observeTracking(nextVideo, 400L)
        val settling = manager.observeTracking(nextVideo, 699L)
        val switched = manager.observeTracking(nextVideo, 700L)

        assertTrue(settling.paused)
        assertEquals("VIDEO_CHANGE_SETTLING", settling.reason)
        assertTrue(switched.switched)
        assertFalse(switched.paused)
        assertEquals(manager.getVideoIdentifier(nextVideo), switched.contentKey)
    }

    @Test
    fun `reset after leaving the app requires a fresh entry confirmation`() {
        val manager = KakaotalkManager()
        val video = video("고양이 영상")
        manager.observeTracking(video, 0L)
        manager.observeTracking(video, 300L)
        manager.resetTracking()

        val firstFrameAfterReturn = manager.observeTracking(video, 400L)
        val confirmedAfterReturn = manager.observeTracking(video, 700L)

        assertFalse(firstFrameAfterReturn.retain)
        assertEquals("VIDEO_CHANGE_PENDING", firstFrameAfterReturn.reason)
        assertTrue(confirmedAfterReturn.retain)
        assertEquals("STABLE_VIDEO", confirmedAfterReturn.reason)
    }

    @Test
    fun `comment overlay pauses the same video and outside evidence eventually exits`() {
        val manager = KakaotalkManager()
        val video = video("고양이 영상")
        assertEquals(ShortformScreenState.OVERLAY, manager.detectShortformScreen(overlay()).state)
        assertFalse(manager.isShortformSection(overlay()))
        manager.observeTracking(video, 0L)
        manager.observeTracking(video, 300L)

        val overlay = manager.observeTracking(overlay(), 1_000L)
        val briefGap = manager.observeTracking(snapshot("친구"), 2_000L)
        val exited = manager.observeTracking(snapshot("친구"), 2_501L)

        assertEquals("PLAYER_OVERLAY", overlay.reason)
        assertTrue(overlay.retain && overlay.paused)
        assertEquals(manager.getVideoIdentifier(video), overlay.contentKey)
        assertTrue(briefGap.retain && briefGap.paused)
        assertFalse(exited.retain)
        assertEquals("LEFT_SHORTFORM", exited.reason)
    }

    @Test
    fun `explicit ad ends an active session immediately`() {
        val manager = KakaotalkManager()
        val video = video("일반 영상")
        manager.observeTracking(video, 0L)
        manager.observeTracking(video, 300L)

        val ad = manager.observeTracking(video("브랜드 소개", "광고"), 600L)

        assertFalse(ad.retain)
        assertEquals("EXPLICIT_AD", ad.reason)
        assertNull(ad.contentKey)
    }

    @Test
    fun `session tracker excludes overlay time and counts one qualified view`() {
        var now = 0L
        val manager = KakaotalkManager()
        val completed = mutableListOf<CompletedShortformSession>()
        val deltas = mutableListOf<ShortformCountDelta>()
        val tracker = ShortformSessionTracker(
            clockMs = { now },
            idFactory = { "kakao-session" },
            onCountDelta = deltas::add,
            onSessionCompleted = completed::add
        )
        val video = video("고양이 영상")

        fun observe(snapshot: ScreenSnapshot) {
            val decision = manager.observeTracking(snapshot, now)
            if (decision.retain && decision.contentKey != null) {
                tracker.activeViewSessionId()?.takeIf { tracker.isProvisional() }?.let { sessionId ->
                    tracker.resolveProvisional(sessionId, ShortformContentKind.NORMAL, decision.contentKey)
                }
                tracker.onScreenChanged(true, SupportedPlatform.KAKAOTALK, decision.contentKey, ShortformContentKind.NORMAL)
                tracker.setPaused(decision.paused)
            } else if (decision.candidateKey != null) {
                tracker.beginProvisional(SupportedPlatform.KAKAOTALK, decision.candidateKey)
            } else {
                tracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            }
        }

        observe(video)
        now = 300L
        observe(video)
        now = 5_300L
        observe(video)
        tracker.tick()
        now = 5_400L
        observe(overlay())
        now = 35_400L
        observe(overlay())
        now = 35_500L
        observe(video)
        now = 37_001L
        observe(snapshot("친구"))

        assertEquals(listOf(1L), deltas.map { it.count })
        assertEquals(1, completed.size)
        assertEquals(manager.getVideoIdentifier(video), completed.single().contentKey)
        assertEquals(6L, completed.single().durationSec)
        assertEquals(1L, completed.single().count)
    }

    @Test
    fun `rapidly skipped videos shorter than five seconds are not counted`() {
        var now = 0L
        val manager = KakaotalkManager()
        val completed = mutableListOf<CompletedShortformSession>()
        val deltas = mutableListOf<ShortformCountDelta>()
        val tracker = ShortformSessionTracker(
            clockMs = { now },
            idFactory = { "kakao-session" },
            onCountDelta = deltas::add,
            onSessionCompleted = completed::add
        )
        val firstVideo = video("첫 영상")
        val nextVideo = video("다음 영상")

        fun observe(snapshot: ScreenSnapshot) {
            val decision = manager.observeTracking(snapshot, now)
            if (decision.retain && decision.contentKey != null) {
                tracker.activeViewSessionId()?.takeIf { tracker.isProvisional() }?.let { sessionId ->
                    tracker.resolveProvisional(sessionId, ShortformContentKind.NORMAL, decision.contentKey)
                }
                tracker.onScreenChanged(true, SupportedPlatform.KAKAOTALK, decision.contentKey, ShortformContentKind.NORMAL)
                tracker.setPaused(decision.paused)
            } else if (decision.candidateKey != null) {
                tracker.beginProvisional(SupportedPlatform.KAKAOTALK, decision.candidateKey)
            } else {
                tracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            }
        }

        observe(firstVideo)
        now = 300L
        observe(firstVideo)
        now = 4_300L
        observe(firstVideo)
        tracker.tick()
        now = 4_350L
        observe(nextVideo)
        now = 4_650L
        observe(nextVideo)
        now = 9_000L
        observe(snapshot("친구"))

        assertTrue(deltas.isEmpty())
        assertTrue(completed.isEmpty())
    }

    private fun video(vararg text: String) = snapshot("숏폼", "좋아요", *text)
    private fun snapshot(vararg text: String) = ScreenSnapshot(text.toList(), emptyList())

    private fun overlay() = ScreenSnapshot(
        texts = listOf("댓글", "보내기"),
        viewIds = emptyList(),
        elements = listOf(
            ScreenElement(
                text = null,
                contentDescription = null,
                viewId = null,
                className = "android.widget.BottomSheet",
                bounds = NormalizedBounds(0f, .5f, 1f, 1f),
                isSelected = false
            )
        )
    )
}
