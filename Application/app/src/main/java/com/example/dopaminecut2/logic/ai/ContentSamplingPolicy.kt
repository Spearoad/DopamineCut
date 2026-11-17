package com.example.dopaminecut2.logic.ai

/** Shared YouTube/KakaoTalk sampling contract; CV decisions and OCR text remain separate. */
object ContentSamplingPolicy {
    const val INTERVAL_MS = 1_000L
    const val MAX_TEXT_CHARS = 500
}

/** Thread-safe admission gate: no suspended waiters and no burst after a slow capture. */
class FrameCaptureGate {
    private var sequence = 0L
    private var active: Long? = null
    private var lastStarted: Long? = null

    @Synchronized fun acquire(nowMs: Long, intervalMs: Long): Long? {
        require(intervalMs > 0)
        if (active != null || lastStarted?.let { nowMs - it < intervalMs } == true) return null
        return (++sequence).also { active = it; lastStarted = nowMs }
    }

    @Synchronized fun release(lease: Long) {
        if (active == lease) active = null
    }
}
