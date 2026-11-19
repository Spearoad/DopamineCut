package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.InstagramManager
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.logic.manager.ContentTrackingCommand
import com.example.dopaminecut2.logic.manager.ContentTrackingContext
import com.example.dopaminecut2.session.ServiceSession
import kotlinx.coroutines.*

data class InstagramFrameToken(val owner: ServiceSession, val contentKey: String, val generation: Long)
data class InstagramRecognitionState(val screen: ShortformScreenState = ShortformScreenState.OUTSIDE,
    val contentKey: String? = null, val kind: ShortformContentKind = ShortformContentKind.UNKNOWN,
    val paused: Boolean = true, val generation: Long = 0, val reason: String = "STOPPED")

/** Main-confined, platform-owned tracking/inference state. No storage, Firebase or image retention. */
class InstagramRecognitionPipeline(private val scope: CoroutineScope, private val manager: InstagramManager,
    private val now: () -> Long, private val owner: () -> ServiceSession?,
    private val infer: suspend (InstagramFrameToken) -> InstagramContentPrediction?,
    private val emit: (InstagramRecognitionState) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}, private val settleMs: Long = 150) {
    var state = InstagramRecognitionState()
        private set
    private var currentOwner: ServiceSession? = null
    private var job: Job? = null
    private var generation = 0L
    private var lastAttempt: Long? = null
    private var failures = 0
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
        // 댓글·공유창이 열렸을 때 / 광고가 나왔을 때 숏폼 영상 수가 증가하지 않도록(새 키를 채택하지 않도록) decision.paused 코드 이동
        if (decision.paused) {
            if (state.contentKey == null) return
            if (state.screen != ShortformScreenState.OVERLAY) invalidate()

            publish(state.copy(
                screen = ShortformScreenState.OVERLAY,
                paused = true,
                generation = generation,
                reason = decision.reason ))
            return
        }
        val key = decision.contentKey ?: return

        if (state.contentKey != key) {
            invalidate()
            failures = 0
            lastAttempt = null
            knownNonReel = false
            publish(InstagramRecognitionState(ShortformScreenState.INSIDE, key,
                generation = generation, reason = "AWAITING_CV"))
        } else publish(state.copy(screen = ShortformScreenState.INSIDE,
            paused = state.kind != ShortformContentKind.NORMAL, reason = decision.reason))

        val nonReelFresh = knownNonReel && lastAttempt?.let { now() - it < 15_000 } == true
        when (val command = manager.contentTrackingCommand(snapshot, ContentTrackingContext(
            key, state.contentKey, state.kind, job?.isActive == true, nonReelFresh))) {
            ContentTrackingCommand.RetryClassification, is ContentTrackingCommand.BeginClassification -> requestInference(key)
            // 같은 부류가 적용되었더라도, 취소 요청이 있으면 진행 중인 CV의 토큰을 무효화해야하므로 코드 내용 변경
            is ContentTrackingCommand.Track -> {
                if (command.cancelInference && job?.isActive == true) invalidate()
                if (command.kind != state.kind || state.generation != generation) {
                    publish(state.copy(
                        kind = command.kind,
                        paused = command.kind != ShortformContentKind.NORMAL,
                        generation = generation
                    ))
                }
            }
            ContentTrackingCommand.Wait -> Unit
        }
    }

    private fun requestInference(key: String) {
        val timestamp = now()
        // Known non-reel results expire as well: a single false negative cannot suppress a key forever.
        val interval = if (knownNonReel || failures >= 3) 15_000L else 1_000L shl failures.coerceAtMost(2)
        if (lastAttempt?.let { timestamp - it < interval } == true) return
        val token = InstagramFrameToken(currentOwner ?: return, key, generation)
        lastAttempt = timestamp
        job = scope.launch {
            try {
                delay(settleMs)
                if (!accepts(token)) return@launch
                val prediction = infer(token)
                if (!accepts(token)) return@launch
                knownNonReel = prediction?.uiClass?.isSafeNonReelScreen == true
                val kind = prediction?.contentKind ?: ShortformContentKind.UNKNOWN
                failures = if (kind == ShortformContentKind.UNKNOWN) failures + 1 else 0
                publish(state.copy(kind = kind, paused = kind != ShortformContentKind.NORMAL,
                    reason = prediction?.uiClass?.name ?: "INFERENCE_UNAVAILABLE"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (accepts(token)) {
                    failures++
                    publish(state.copy(kind = ShortformContentKind.UNKNOWN, paused = true, reason = "INFERENCE_FAILED"))
                    onFailure(error)
                }
            }
        }
    }

    fun accepts(token: InstagramFrameToken): Boolean = token.owner == owner() &&
        token.owner == currentOwner && token.generation == generation && token.contentKey == state.contentKey &&
        state.screen == ShortformScreenState.INSIDE

    private fun invalidate() { generation++; job?.cancel(); job = null }
    private fun publish(value: InstagramRecognitionState) { state = value; emit(value) }
    fun stop() {
        invalidate()
        currentOwner = null
        manager.resetTracking()
        knownNonReel = false
        lastAttempt = null
        failures = 0
        publish(InstagramRecognitionState(generation = generation))
    }
}
