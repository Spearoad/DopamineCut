package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.session.ServiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class KakaotalkFrameToken(val owner: ServiceSession, val contentKey: String, val generation: Long)

data class KakaotalkRecognitionState(
    val screen: ShortformScreenState = ShortformScreenState.OUTSIDE,
    val contentKey: String? = null,
    val kind: ShortformContentKind = ShortformContentKind.UNKNOWN,
    /** Pausing the player is separate from waiting for CV (unconfirmed time stays provisional). */
    val paused: Boolean = true,
    val excluded: Boolean = false,
    val generation: Long = 0,
    val reason: String = "STOPPED",
    val attempts: Int = 0,
    val executionFailures: Int = 0,
    val unconfirmedResults: Int = 0
)

/** Main-confined platform state. Owns jobs/tokens, but never bitmaps, storage or Firebase. */
class KakaotalkRecognitionPipeline(
    private val scope: CoroutineScope,
    private val manager: KakaotalkManager,
    private val now: () -> Long,
    private val owner: () -> ServiceSession?,
    private val infer: suspend (KakaotalkFrameToken) -> KakaotalkContentPrediction?,
    private val emit: (KakaotalkRecognitionState) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val cvAvailable: () -> Boolean = { true },
    private val trace: (String) -> Unit = {},
    // observeTracking already requires a stable identity for 300 ms. Do not suspend
    // the production request on a second timer before capture; use that observed stability.
    private val settleMs: Long = 0,
    private val requestTimeoutMs: Long = 5_000
) {
    var state = KakaotalkRecognitionState()
        private set

    private class Request(val token: KakaotalkFrameToken, val startedAtMs: Long) { var job: Job? = null }
    private var currentOwner: ServiceSession? = null
    private var request: Request? = null
    private var generation = 0L
    private var lastAttempt: Long? = null
    private var attempts = 0
    private var executionFailures = 0
    private var unconfirmedResults = 0

    val inferenceRunning: Boolean get() = request?.job?.isActive == true

    init { require(settleMs >= 0 && requestTimeoutMs > settleMs) }

    fun observe(snapshot: ScreenSnapshot) {
        val nextOwner = owner() ?: run { stop(); return }
        if (currentOwner != nextOwner) { stop(); currentOwner = nextOwner }
        val decision = manager.observeTracking(snapshot, now())
        if (!decision.retain) {
            val candidate = decision.candidateKey
            if (state.contentKey != candidate || request != null || state.screen == ShortformScreenState.INSIDE) {
                invalidate()
                resetAttempts()
            }
            publish(KakaotalkRecognitionState(
                screen = if (candidate == null) ShortformScreenState.OUTSIDE else ShortformScreenState.CANDIDATE,
                contentKey = candidate,
                kind = if (decision.reason == "EXPLICIT_AD") ShortformContentKind.AD else ShortformContentKind.UNKNOWN,
                paused = candidate == null,
                generation = generation,
                reason = decision.reason
            ))
            return
        }
        val key = decision.contentKey ?: return
        if (decision.paused) {
            val screen = if (decision.reason == "PLAYER_OVERLAY") ShortformScreenState.OVERLAY else ShortformScreenState.CANDIDATE
            if (!state.paused || state.screen != screen || request != null) {
                invalidate()
                // A cancelled capture is not a failed model result. Resume can retry immediately.
                lastAttempt = null
            }
            publish(state.copy(screen = screen, contentKey = key, paused = true,
                generation = generation, reason = decision.reason))
            return
        }
        if (state.contentKey != key) {
            // Cancel BEFORE admitting a new key. Never wait for an old request's finally block.
            invalidate()
            resetAttempts()
            publish(KakaotalkRecognitionState(ShortformScreenState.INSIDE, key,
                paused = false, generation = generation, reason = "AWAITING_CV"))
        } else {
            publish(state.copy(screen = ShortformScreenState.INSIDE, paused = false, reason = decision.reason))
        }

        if (!cvAvailable()) {
            publish(state.copy(kind = ShortformContentKind.NORMAL, excluded = false, reason = "ACCESSIBILITY_FALLBACK"))
            return
        }
        if (state.kind == ShortformContentKind.NORMAL) return
        if (request?.let { now() - it.startedAtMs >= requestTimeoutMs } == true) {
            // Observation-side deadline also recovers if the coroutine's timer/resume is delayed.
            invalidate()
            lastAttempt = now()
            failed("REQUEST_EXPIRED")
            return
        }
        if (inferenceRunning) return
        val backoffShift = (executionFailures + unconfirmedResults - 1).coerceIn(0, 3)
        val interval = if (state.excluded || state.kind == ShortformContentKind.AD) 15_000L
        else if (executionFailures + unconfirmedResults == 0) 0L
        else (350L shl backoffShift)
        if (lastAttempt?.let { now() - it < interval } == true) return
        startRequest(key)
    }

    private fun startRequest(key: String) {
        val slot = Request(KakaotalkFrameToken(currentOwner ?: return, key, generation), now())
        request = slot
        lastAttempt = now()
        attempts++
        // LAZY ensures ownership is installed even when an injected inference returns synchronously.
        slot.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prediction = withTimeout(requestTimeoutMs) {
                    trace("SETTLE_START key=${key.take(12)} generation=${slot.token.generation}")
                    delay(settleMs)
                    trace("SETTLE_END key=${key.take(12)} generation=${slot.token.generation}")
                    if (!accepts(slot.token, "BEFORE_INFER") || request !== slot) return@withTimeout null
                    infer(slot.token)
                }
                if (!accepts(slot.token, "AFTER_INFER") || request !== slot) return@launch
                when {
                    prediction == null -> {
                        trace("INFERENCE_NULL key=${key.take(12)} generation=${slot.token.generation}")
                        failed("INFERENCE_UNAVAILABLE")
                    }
                    prediction.uiClass == KakaotalkUiClass.UNKNOWN -> {
                        unconfirmedResults++
                        publish(state.copy(kind = ShortformContentKind.UNKNOWN, excluded = false,
                            reason = "MODEL_UNCONFIRMED"))
                    }
                    else -> {
                        executionFailures = 0
                        unconfirmedResults = 0
                        publish(state.copy(kind = prediction.contentKind,
                            excluded = prediction.uiClass.isNonShortformScreen, reason = prediction.uiClass.name))
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                if (accepts(slot.token, "ON_TIMEOUT") && request === slot) failed("REQUEST_TIMEOUT")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (accepts(slot.token, "ON_ERROR") && request === slot) {
                    failed("INFERENCE_FAILED")
                    onFailure(error)
                }
            } finally {
                // A stale completion must never erase a newer video's request.
                if (request === slot) request = null
            }
        }
        slot.job?.start()
    }

    fun accepts(token: KakaotalkFrameToken): Boolean = accepts(token, "EXTERNAL")

    private fun accepts(token: KakaotalkFrameToken, site: String): Boolean {
        val activeOwner = owner()
        val ownerMatchesActive = activeOwner != null && token.owner == activeOwner
        val ownerMatchesCurrent = token.owner == currentOwner
        val generationMatches = token.generation == generation
        val keyMatches = token.contentKey == state.contentKey
        val insideScreen = state.screen == ShortformScreenState.INSIDE
        val notPaused = !state.paused
        val ok = ownerMatchesActive && ownerMatchesCurrent && generationMatches && keyMatches && insideScreen && notPaused
        if (!ok) {
            trace(
                "REJECT_TOKEN site=$site key=${token.contentKey.take(12)} " +
                        "tokenGen=${token.generation} stateGen=$generation " +
                        "ownerActive=$ownerMatchesActive ownerCurrent=$ownerMatchesCurrent " +
                        "keyMatch=$keyMatches screen=${state.screen} paused=${state.paused}"
            )
        }
        return ok
    }

    private fun failed(reason: String) {
        executionFailures++
        publish(state.copy(kind = ShortformContentKind.UNKNOWN, excluded = false, reason = reason))
    }

    private fun publish(value: KakaotalkRecognitionState) {
        state = value.copy(generation = generation, attempts = attempts,
            executionFailures = executionFailures, unconfirmedResults = unconfirmedResults)
        emit(state)
    }

    private fun invalidate() {
        generation++
        val previous = request
        request = null
        previous?.job?.cancel()
    }

    private fun resetAttempts() { lastAttempt = null; attempts = 0; executionFailures = 0; unconfirmedResults = 0 }

    fun stop() {
        invalidate()
        currentOwner = null
        manager.resetTracking()
        resetAttempts()
        publish(KakaotalkRecognitionState(generation = generation))
    }
}