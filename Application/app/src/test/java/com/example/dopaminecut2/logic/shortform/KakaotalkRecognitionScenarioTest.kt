package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.NormalizedBounds
import com.example.dopaminecut2.logic.manager.ScreenElement
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.session.ServiceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개선 작업 지시의 카카오톡 필수 상황을 진입 탐지 → 영상 식별 → 유형 분류 → 시간·횟수 집계까지 이어서 검증한다.
 * A 추적(KakaotalkManager), 파이프라인, B 판정(가짜 모델 결과), 세션 반영(KakaotalkSessionBinder),
 * 집계(ShortformSessionTracker)는 실제 코드를 쓰고, 화면 캡처·ONNX 추론만 대신한다.
 */
class KakaotalkRecognitionScenarioTest {
    private class Harness {
        var now = 0L
        val manager = KakaotalkManager()
        val counts = mutableListOf<ShortformCountDelta>()
        val completed = mutableListOf<CompletedShortformSession>()
        private var nextSession = 0
        val tracker = ShortformSessionTracker(
            clockMs = { now },
            idFactory = { "session-${++nextSession}" },
            onCountDelta = counts::add,
            onSessionCompleted = completed::add
        )

        /** 모델이 돌려줄 판정. 기본은 일반 숏폼. */
        var verdict: (KakaotalkFrameToken) -> KakaotalkUiClass = { KakaotalkUiClass.SHORTFORM_NORMAL }
        /** 설정하면 추론이 이 값이 완료될 때까지 기다린다(비동기 결과 지연 재현). */
        var pending: CompletableDeferred<KakaotalkUiClass>? = null
        val inferenceRequests = mutableListOf<KakaotalkFrameToken>()

        private var contentKey: String? = null
        private var screen = ShortformScreenState.OUTSIDE
        var ocrBufferResets = 0
        private val owner = ServiceSession("user", "2026-11-19")

        val pipeline = KakaotalkRecognitionPipeline(
            scope = CoroutineScope(Dispatchers.Unconfined),
            manager = manager,
            now = { now },
            owner = { owner },
            infer = { token ->
                inferenceRequests += token
                val uiClass = pending?.await() ?: verdict(token)
                KakaotalkContentPrediction(uiClass, uiClass.toShortformContentKind(), 0.99f, emptyMap())
            },
            emit = ::apply
        )

        /** AppBlockService.applyKakaotalkState와 같은 순서로 반영한다. */
        private fun apply(state: KakaotalkRecognitionState) {
            val change = KakaotalkSessionBinder.change(contentKey, screen, state)
            if (change.newVideo) ocrBufferResets++
            contentKey = state.contentKey
            screen = state.screen
            KakaotalkSessionBinder.bind(tracker, state, change.newVideo) { false }
        }

        /** 같은 화면을 [durationMs] 동안 150ms 간격으로 관측한다(서비스 화면 갱신 주기). */
        fun show(snapshot: ScreenSnapshot, durationMs: Long) {
            val end = now + durationMs
            while (now < end) {
                pipeline.observe(snapshot)
                tracker.tick()
                now += 150L
            }
        }

        fun leave() {
            show(ScreenSnapshot(listOf("채팅"), emptyList()), 3_000L)
        }

        val totalCount get() = counts.sumOf { it.count }
        val activeSession get() = tracker.activeViewSessionId()
    }

    @Test
    fun `same video for 20 seconds counts once with one session and growing time`() {
        val h = Harness()
        h.show(video("고양이 영상"), 5_500L)
        val session = h.activeSession
        assertEquals(1L, h.totalCount)

        h.show(video("고양이 영상", "좋아요 1.3만", "00:12"), 14_500L)
        assertEquals("같은 영상에서는 세션을 새로 만들지 않는다", session, h.activeSession)
        assertEquals(1L, h.totalCount)
        assertEquals("모델 판정은 영상당 한 번", 1, h.inferenceRequests.size)

        h.leave()
        val finished = h.completed.single()
        assertEquals(1L, finished.count)
        assertTrue("시청 시간 ${finished.durationSec}초", finished.durationSec in 19L..20L)
    }

    @Test
    fun `n different videos each 10 seconds count n times with separate sessions`() {
        val h = Harness()
        val titles = listOf("첫 번째 영상", "두 번째 영상", "세 번째 영상")
        titles.forEach { h.show(video(it), 10_000L) }
        h.leave()

        assertEquals(3L, h.totalCount)
        assertEquals(3, h.completed.size)
        assertEquals(3, h.completed.map { it.viewSessionId }.toSet().size)
        assertEquals(3, h.completed.map { it.contentKey }.toSet().size)
        assertTrue(h.completed.all { it.count == 1L && it.durationSec >= 9L })
    }

    @Test
    fun `comment or share sheet pauses CV and time then resumes the same session`() {
        val h = Harness()
        h.show(video("고양이 영상"), 6_000L)
        val session = h.activeSession
        val requestsBefore = h.inferenceRequests.size
        val resetsBefore = h.ocrBufferResets

        h.show(sheet("댓글", "댓글 달기"), 10_000L)
        assertEquals("창이 열린 동안 CV 새 작업 없음", requestsBefore, h.inferenceRequests.size)
        assertEquals("세션 유지", session, h.activeSession)

        h.show(video("고양이 영상"), 4_000L)
        h.show(sheet("공유", "링크 복사"), 10_000L)
        h.show(video("고양이 영상"), 4_000L)
        assertEquals("복귀 후 같은 세션으로 이어짐", session, h.activeSession)
        assertEquals("복귀 후 재판정 없음", requestsBefore, h.inferenceRequests.size)
        assertEquals("같은 영상의 누적 OCR 보존", resetsBefore, h.ocrBufferResets)

        h.leave()
        val finished = h.completed.single()
        assertEquals(1L, finished.count)
        assertTrue("창이 열린 20초는 시간에서 제외: ${finished.durationSec}초", finished.durationSec in 13L..14L)
    }

    @Test
    fun `sheet opened before the verdict cancels CV and judges again only after return`() {
        val h = Harness()
        val unanswered = CompletableDeferred<KakaotalkUiClass>()
        h.pending = unanswered
        h.show(video("고양이 영상"), 1_000L)
        assertEquals(1, h.inferenceRequests.size)

        h.show(sheet("댓글", "댓글 달기"), 10_000L)
        assertEquals("창이 열린 동안 새 요청 없음", 1, h.inferenceRequests.size)
        unanswered.complete(KakaotalkUiClass.SHORTFORM_AD) // 취소된 요청의 늦은 결과
        assertEquals(0L, h.totalCount)

        h.pending = null
        h.show(video("고양이 영상"), 8_000L)
        assertEquals("복귀 후 다시 판정", 2, h.inferenceRequests.size)
        h.leave()
        assertEquals(1L, h.totalCount)
        assertTrue("판정 전 대기와 창이 열린 시간은 제외: ${h.completed.single().durationSec}초",
            h.completed.single().durationSec in 8L..9L)
    }

    @Test
    fun `sheet keeps the OCR buffer but a different video discards it`() {
        val inside = KakaotalkRecognitionState(ShortformScreenState.INSIDE, "video-a",
            ShortformContentKind.NORMAL, paused = false)
        val sheetOpen = inside.copy(screen = ShortformScreenState.OVERLAY, paused = true)
        val otherVideo = inside.copy(contentKey = "video-b", kind = ShortformContentKind.UNKNOWN)

        val opened = KakaotalkSessionBinder.change("video-a", ShortformScreenState.INSIDE, sheetOpen)
        val returned = KakaotalkSessionBinder.change("video-a", ShortformScreenState.OVERLAY, inside)
        val switched = KakaotalkSessionBinder.change("video-a", ShortformScreenState.INSIDE, otherVideo)
        val left = KakaotalkSessionBinder.change("video-a", ShortformScreenState.INSIDE, KakaotalkRecognitionState())

        assertTrue(opened.newFrame && !opened.newVideo)
        assertTrue(returned.newFrame && !returned.newVideo)
        assertTrue(switched.newFrame && switched.newVideo)
        assertTrue(left.newFrame && left.newVideo)
    }

    @Test
    fun `ads and non-shortform screens add neither shortform time nor count`() {
        val h = Harness()
        h.verdict = { KakaotalkUiClass.SHORTFORM_AD }
        h.show(video("브랜드 영상"), 10_000L)
        h.verdict = { KakaotalkUiClass.NOW_OPENCHAT }
        h.show(video("오픈채팅 피드"), 10_000L)
        h.verdict = { KakaotalkUiClass.SHORTFORM_NORMAL }
        h.show(video("세일 안내", "광고"), 10_000L)
        h.leave()

        assertEquals(0L, h.totalCount)
        assertTrue(h.completed.all { it.count == 0L && it.durationSec == 0L })
    }

    @Test
    fun `late CV result of the previous video is never applied to the next video`() {
        val h = Harness()
        val lateResult = CompletableDeferred<KakaotalkUiClass>()
        h.pending = lateResult
        h.show(video("이전 영상"), 1_000L)
        val previousRequest = h.inferenceRequests.single()

        h.pending = null
        h.show(video("다음 영상"), 1_000L)
        lateResult.complete(KakaotalkUiClass.SHORTFORM_AD)
        h.show(video("다음 영상"), 9_000L)
        h.leave()

        val next = h.completed.single()
        assertNotEquals(previousRequest.contentKey, next.contentKey)
        assertEquals("다음 영상은 자기 판정(일반)으로 집계", 1L, next.count)
    }

    @Test
    fun `revisiting one of the last three videos keeps time but does not count again`() {
        val h = Harness()
        h.show(video("영상 A"), 6_000L)
        h.show(video("영상 B"), 6_000L)
        h.show(video("영상 A"), 6_000L)
        h.leave()

        assertEquals(2L, h.totalCount)
        assertEquals(3, h.completed.size)
        assertTrue(h.completed.last().durationSec >= 5L)
    }

    private fun video(vararg text: String) = ScreenSnapshot(listOf("숏폼", "좋아요", *text), emptyList())

    /** 영상 위에 열린 댓글·공유창. 카카오톡 실제 구조는 실기기에서 확인해야 한다. */
    private fun sheet(vararg text: String) = ScreenSnapshot(
        texts = text.toList(),
        viewIds = emptyList(),
        elements = listOf(
            ScreenElement(
                text = null, contentDescription = null, viewId = null,
                className = "android.widget.BottomSheet",
                bounds = NormalizedBounds(0f, .5f, 1f, 1f), isSelected = false
            )
        )
    )
}
