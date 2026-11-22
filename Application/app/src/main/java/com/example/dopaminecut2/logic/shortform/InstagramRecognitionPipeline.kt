package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.InstagramManager
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.logic.manager.ContentTrackingCommand
import com.example.dopaminecut2.logic.manager.ContentTrackingContext
import com.example.dopaminecut2.session.ServiceSession
import kotlinx.coroutines.*

data class InstagramFrameToken(val owner: ServiceSession, val contentKey: String, val generation: Long)
data class InstagramRecognitionState(
    val screen: ShortformScreenState = ShortformScreenState.OUTSIDE,
    val contentKey: String? = null,
    val kind: ShortformContentKind = ShortformContentKind.UNKNOWN,
    val paused: Boolean = true,
    val generation: Long = 0,
    val reason: String = "STOPPED",
    val excluded: Boolean = false
)

/** Main-confined, platform-owned tracking/inference state. No storage, Firebase or image retention. */
class InstagramRecognitionPipeline(
    private val scope: CoroutineScope,
    private val manager: InstagramManager,
    private val now: () -> Long,
    private val owner: () -> ServiceSession?,
    private val infer: suspend (InstagramFrameToken) -> InstagramContentPrediction?,
    private val emit: (InstagramRecognitionState) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val settleMs: Long = 150,
    private val cvAvailable: () -> Boolean = { true }
) {
    var state = InstagramRecognitionState()
        private set
    private val confirmation = ContentKindConfirmation()
    private var nonReelStreak = 0
    private var lastNonReelObservedAt: Long? = null
    private var currentOwner: ServiceSession? = null
    private var job: Job? = null
    private var generation = 0L
    private var lastAttempt: Long? = null
    private var failures = 0
    private var captureSkips = 0
    private var knownNonReel = false

    fun observe(snapshot: ScreenSnapshot) {
        val nextOwner = owner() ?: run { stop(); return }
        if (currentOwner != nextOwner) { stop(); currentOwner = nextOwner }
        val decision = manager.observeTracking(snapshot, now())
        if (!decision.retain) {
            // Do not reset the coordinator here: the first observed frame is still settling.
            if (state.contentKey != null || job?.isActive == true) invalidate()
            knownNonReel = false
            publish(InstagramRecognitionState(generation = generation, reason = decision.reason))
            return
        }
        // 댓글·공유창이 열렸을 때 / 일시정지 시 새 키를 채택하지 않도록 유지
        if (decision.paused) {
            if (state.contentKey == null) return
            if (state.screen != ShortformScreenState.OVERLAY) invalidate()

            publish(
                state.copy(
                    screen = ShortformScreenState.OVERLAY,
                    paused = true,
                    generation = generation,
                    reason = decision.reason
                )
            )
            return
        }
        val key = decision.contentKey ?: return
        val cvReady = cvAvailable()

        if (state.contentKey != key) {
            invalidate()
            failures = 0
            captureSkips = 0
            lastAttempt = null
            knownNonReel = false
            publish(
                InstagramRecognitionState(
                    screen = ShortformScreenState.INSIDE,
                    contentKey = key,
                    kind = ShortformContentKind.UNKNOWN,
                    paused = !cvReady, // CV가 가능할 때만 임시 세션 대기 시간 기록
                    generation = generation,
                    reason = if (cvReady) "AWAITING_CV" else "CV_UNAVAILABLE",
                    excluded = false
                )
            )
        } else {
            val shouldPause = state.excluded ||
                    state.kind == ShortformContentKind.AD ||
                    (state.kind == ShortformContentKind.UNKNOWN && (!cvReady || failures > 0 || captureSkips >= 2))
            publish(
                state.copy(
                    screen = ShortformScreenState.INSIDE,
                    paused = shouldPause,
                    reason = if (!cvReady && state.kind == ShortformContentKind.UNKNOWN) "CV_UNAVAILABLE" else decision.reason
                )
            )
        }

        val nonReelFresh = knownNonReel && lastAttempt?.let { now() - it < 15_000 } == true
        when (val command = manager.contentTrackingCommand(
            snapshot,
            ContentTrackingContext(key, state.contentKey, state.kind, job?.isActive == true, nonReelFresh)
        )) {
            ContentTrackingCommand.RetryClassification,
            is ContentTrackingCommand.BeginClassification -> if (cvReady) requestInference(key)

            is ContentTrackingCommand.Track -> {
                if (command.cancelInference && job?.isActive == true) invalidate()
                val isAd = command.kind == ShortformContentKind.AD
                if (command.kind != state.kind || state.generation != generation || state.excluded != isAd) {
                    publish(
                        state.copy(
                            kind = command.kind,
                            paused = !countsWatchTime(command.kind),
                            generation = generation,
                            excluded = isAd
                        )
                    )
                }
            }
            ContentTrackingCommand.Wait -> Unit
        }
    }

    private fun countsWatchTime(kind: ShortformContentKind): Boolean = when (kind) {
        ShortformContentKind.NORMAL,
        ShortformContentKind.LIVE,
        ShortformContentKind.PHOTO_POST -> true
        else -> false
    }

    private fun retryIntervalMs(): Long = when {
        knownNonReel || failures >= 3 || captureSkips >= 4 -> 15_000L
        failures > 0 -> 1_000L shl (failures - 1).coerceAtMost(2)
        captureSkips > 0 -> 400L shl (captureSkips - 1).coerceAtMost(2)
        else -> 850L
    }

    private fun requestInference(key: String) {
        val timestamp = now()
        // Known non-reel results expire as well: a single false negative cannot suppress a key forever.
        val interval = retryIntervalMs()
        if (lastAttempt?.let { timestamp - it < interval } == true) return
        val token = InstagramFrameToken(currentOwner ?: return, key, generation)
        if (knownNonReel) {
            // 15초 백오프 만료 후 재검증 진입 시 2프레임 연속 판정이 이어지도록 초기화
            knownNonReel = false
            resetConfirmation()
        }
        lastAttempt = timestamp
        job = scope.launch {
            try {
                delay(settleMs)
                if (!accepts(token)) return@launch
                val prediction = infer(token)
                if (!accepts(token)) return@launch

                if (prediction == null) {
                    // 1회 캡처 충돌/스킵 시 기존 1차 관측과 임시 세션을 보존하고 400ms 뒤 빠르게 재시도
                    captureSkips++
                    if (captureSkips >= 2) resetConfirmation()
                    publish(
                        state.copy(
                            kind = ShortformContentKind.UNKNOWN,
                            paused = state.excluded || failures > 0 || captureSkips >= 2,
                            excluded = state.excluded,
                            reason = "INFERENCE_UNAVAILABLE"
                        )
                    )
                    return@launch
                }

                captureSkips = 0
                val isNonReel = prediction.uiClass?.isSafeNonReelScreen == true
                if (isNonReel) {
                    // 비릴스 안전 구역은 광고(AD)와 교차 합의되지 않도록 별도 2프레임 카운터로 검증
                    confirmation.reset()
                    val observedAt = now()
                    nonReelStreak = if (lastNonReelObservedAt?.let { observedAt - it <= NON_REEL_CONFIRM_WINDOW_MS } == true) {
                        nonReelStreak + 1
                    } else {
                        1
                    }
                    lastNonReelObservedAt = observedAt
                    failures = 0
                    if (nonReelStreak < 2) {
                        publish(
                            state.copy(
                                kind = ShortformContentKind.UNKNOWN,
                                paused = state.excluded,
                                excluded = state.excluded,
                                reason = prediction.uiClass?.name ?: "NON_REEL_OBSERVED"
                            )
                        )
                        return@launch
                    }
                    nonReelStreak = 0
                    lastNonReelObservedAt = null
                    knownNonReel = true
                    publish(
                        state.copy(
                            kind = ShortformContentKind.UNKNOWN,
                            paused = true,
                            excluded = true,
                            reason = prediction.uiClass?.name ?: "NON_REEL_CONFIRMED"
                        )
                    )
                    return@launch
                }

                nonReelStreak = 0
                lastNonReelObservedAt = null
                val rawKind = prediction.contentKind

                // 2프레임 연속 검증 (일반 릴스 / 광고)
                val confirmedKind = confirmation.observe(rawKind, now())
                if (confirmedKind == ShortformContentKind.UNKNOWN) {
                    // 모델 불확실(UNKNOWN)일 때만 실패 백오프 증가, 1차 정상 관측 후 2차 대기는 850ms 뒤 재시도
                    failures = if (rawKind == ShortformContentKind.UNKNOWN) failures + 1 else 0
                    publish(
                        state.copy(
                            kind = ShortformContentKind.UNKNOWN,
                            paused = state.excluded || failures > 0,
                            excluded = state.excluded,
                            reason = prediction.uiClass?.name ?: "INFERENCE_UNAVAILABLE"
                        )
                    )
                    return@launch
                }

                failures = 0
                knownNonReel = false
                val isAd = confirmedKind == ShortformContentKind.AD
                publish(
                    state.copy(
                        kind = confirmedKind,
                        paused = !countsWatchTime(confirmedKind),
                        excluded = isAd,
                        reason = prediction.uiClass?.name ?: confirmedKind.name
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (accepts(token)) {
                    failures++
                    resetConfirmation()
                    publish(
                        state.copy(
                            kind = ShortformContentKind.UNKNOWN,
                            paused = true,
                            reason = "INFERENCE_FAILED"
                        )
                    )
                    onFailure(error)
                }
            }
        }
    }

    fun accepts(token: InstagramFrameToken): Boolean = token.owner == owner() &&
            token.owner == currentOwner && token.generation == generation &&
            token.contentKey == state.contentKey && state.screen == ShortformScreenState.INSIDE

    private fun resetConfirmation() {
        confirmation.reset()
        nonReelStreak = 0
        lastNonReelObservedAt = null
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
        captureSkips = 0
        resetConfirmation()
    }

    private fun publish(value: InstagramRecognitionState) {
        state = value
        emit(value)
    }

    fun stop() {
        invalidate()
        currentOwner = null
        manager.resetTracking()
        knownNonReel = false
        lastAttempt = null
        failures = 0
        captureSkips = 0
        publish(InstagramRecognitionState(generation = generation))
    }

    private companion object {
        const val NON_REEL_CONFIRM_WINDOW_MS = 4_000L
    }
}