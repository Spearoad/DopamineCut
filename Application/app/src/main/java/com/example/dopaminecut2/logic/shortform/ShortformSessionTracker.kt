package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.domain.SupportedPlatform
import java.util.UUID

data class ShortformCountDelta(
    val viewSessionId: String,
    val platform: SupportedPlatform,
    val contentKey: String,
    val contentKind: ShortformContentKind,
    val count: Long
)

data class CompletedShortformSession(
    val viewSessionId: String,
    val platform: SupportedPlatform,
    val contentKey: String,
    val contentKind: ShortformContentKind,
    val durationSec: Long,
    val count: Long
)

data class ShortformSessionCheckpoint(
    val viewSessionId: String,
    val platform: SupportedPlatform,
    val contentKey: String,
    val contentKind: ShortformContentKind,
    val durationSec: Long,
    val count: Long
)

data class ProvisionalViewCheckpoint(
    val viewSessionId: String,
    val platform: SupportedPlatform,
    val durationSec: Long,
    val ended: Boolean
)

/** 한 화면 체류 세션을 추적한다. 시간 계산에는 반드시 단조 시계를 사용한다. */
class ShortformSessionTracker(
    private val clockMs: () -> Long,
    private val countingPolicy: ShortformCountingPolicy = ShortformCountingPolicy(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val onCountDelta: (ShortformCountDelta) -> Unit = {},
    private val onCheckpoint: (ShortformSessionCheckpoint) -> Unit = {},
    private val onSessionCompleted: (CompletedShortformSession) -> Unit,
    private val onProvisionalCheckpoint: (ProvisionalViewCheckpoint) -> Unit = {},
    private val onProvisionalSettled: (String) -> Unit = {},
    private val admitNormalSession: (SupportedPlatform) -> Boolean = { true }
) {
    private data class ActiveSession(
        val viewSessionId: String,
        val platform: SupportedPlatform,
        var contentKey: String,
        var contentKind: ShortformContentKind,
        var startedAtMs: Long,
        var admissionPending: Boolean = false,
        var reportedCount: Long = 0L,
        var checkpointDurationSec: Long = 0L,
        var pausedAtMs: Long? = null,
        var pausedDurationMs: Long = 0L
    )

    private var active: ActiveSession? = null

    fun activeViewSessionId(): String? = active?.viewSessionId
    fun isProvisional(): Boolean = active?.contentKind == ShortformContentKind.UNKNOWN
    fun isAdmissionPending(): Boolean = active?.admissionPending == true

    /** Start measurement at observed entry, not at CV completion; UNKNOWN stays out of totals. */
    fun beginProvisional(platform: SupportedPlatform, contentKey: String) {
        require(contentKey.isNotBlank())
        val current = active
        if (current?.platform == platform && current.contentKey == contentKey) return
        finishActiveSession()
        active = ActiveSession(idFactory(), platform, contentKey, ShortformContentKind.UNKNOWN, clockMs())
    }

    /** Results only promote the exact captured session. Existing start/pause times and ID survive. */
    fun resolveProvisional(expectedSessionId: String, kind: ShortformContentKind, contentKey: String): Boolean {
        val current = active ?: return false
        if (current.viewSessionId != expectedSessionId || current.contentKind != ShortformContentKind.UNKNOWN ||
            kind == ShortformContentKind.UNKNOWN || contentKey.isBlank()) return false
        if (kind == ShortformContentKind.NORMAL) {
            val admitted = admitNormalSession(current.platform)
            if (active !== current) return false // An intervention can synchronously stop tracking.
            if (!admitted) {
                current.admissionPending = true
                setPaused(true)
                onProvisionalSettled(current.viewSessionId)
                return false
            }
        }
        if (current.admissionPending && kind != ShortformContentKind.AD) {
            // Goal extension or time-only reclassification: never count denied waiting time.
            current.startedAtMs = clockMs()
            current.pausedAtMs = null
            current.pausedDurationMs = 0L
            current.checkpointDurationSec = 0L
            current.admissionPending = false
        }
        onProvisionalSettled(current.viewSessionId)
        if (kind == ShortformContentKind.AD) {
            active = null
            return true
        }
        current.contentKind = kind
        current.contentKey = contentKey
        current.checkpointDurationSec = 0L
        tick()
        return true
    }
    /** Cumulative live progress; callers subtract their last checkpoint, never add it twice. */
    fun activeProgress(): ShortformSessionCheckpoint? = active?.let {
        val result = if (it.admissionPending) ShortformCountResult(0, 0)
            else countingPolicy.result(it.contentKind, elapsedSec(it))
        ShortformSessionCheckpoint(it.viewSessionId, it.platform, it.contentKey, it.contentKind, result.trackedDurationSec, result.count)
    }

    fun onScreenChanged(
        isShortform: Boolean,
        platform: SupportedPlatform?,
        contentKey: String?,
        contentKind: ShortformContentKind
    ) {
        val normalizedKey = contentKey?.trim()?.takeIf(String::isNotEmpty)
        // Missing classification is not evidence of a new video or leaving the player.
        // Preserve the counted flag and session ID, but never accrue uncertain time.
        val existing = active
        if (isShortform && platform != null && normalizedKey != null &&
            contentKind == ShortformContentKind.UNKNOWN &&
            existing?.platform == platform && existing.contentKey == normalizedKey &&
            existing.contentKind != ShortformContentKind.UNKNOWN) {
            setPaused(true)
            return
        }
        val trackable = isShortform && platform != null && normalizedKey != null &&
            contentKind != ShortformContentKind.AD &&
            contentKind != ShortformContentKind.UNKNOWN

        if (!trackable) {
            finishActiveSession()
            return
        }

        val current = active
        if (
            current != null &&
            current.platform == platform &&
            current.contentKey == normalizedKey &&
            current.contentKind == contentKind
        ) {
            return
        }

        // Promotion must pass the same gate as direct normal sessions. Repeated snapshots
        // of a denied video must not finish it and manufacture new counted sessions.
        if (current != null && current.platform == platform && current.contentKey == normalizedKey &&
            current.contentKind == ShortformContentKind.UNKNOWN && contentKind == ShortformContentKind.NORMAL) {
            resolveProvisional(current.viewSessionId, contentKind, normalizedKey!!)
            return
        }

        finishActiveSession()
        if (contentKind == ShortformContentKind.NORMAL) {
            beginProvisional(platform!!, normalizedKey!!)
            active?.viewSessionId?.let { resolveProvisional(it, contentKind, normalizedKey) }
            return
        }
        active = ActiveSession(
            viewSessionId = idFactory(),
            platform = platform,
            contentKey = normalizedKey,
            contentKind = contentKind,
            startedAtMs = clockMs()
        )
    }

    fun tick() {
        val current = active ?: return
        if (current.admissionPending) return
        val durationSec = elapsedSec(current)
        if (current.contentKind == ShortformContentKind.UNKNOWN) {
            reportProvisional(current, durationSec)
            return
        }
        reportCheckpoint(current, durationSec)
        reportCountDelta(current, durationSec)
    }

    /** 댓글/공유 중에는 시간을 제외하되 동일 영상의 세션과 카운트는 유지한다. */
    fun setPaused(paused: Boolean) {
        val current = active ?: return
        if (!paused && current.admissionPending) return
        if (paused && current.pausedAtMs == null) {
            current.pausedAtMs = clockMs()
            if (current.admissionPending) return
            if (current.contentKind == ShortformContentKind.UNKNOWN) {
                reportProvisional(current, elapsedSec(current))
            } else reportCheckpoint(current, elapsedSec(current), force = true)
        } else if (!paused) {
            current.pausedAtMs?.let { start ->
                current.pausedDurationMs += (clockMs() - start).coerceAtLeast(0L)
                current.pausedAtMs = null
            }
        }
    }

    fun release() {
        finishActiveSession()
    }

    private fun finishActiveSession() {
        val current = active ?: return
        // A count callback can trigger an intervention and re-enter this method.
        active = null
        if (current.admissionPending) {
            onProvisionalSettled(current.viewSessionId)
            return
        }
        val durationSec = elapsedSec(current)
        if (current.contentKind == ShortformContentKind.UNKNOWN) {
            reportProvisional(current, durationSec, ended = true)
            if (countingPolicy.result(ShortformContentKind.NORMAL, durationSec).trackedDurationSec == 0L) {
                onProvisionalSettled(current.viewSessionId)
            }
            return
        }
        val result = countingPolicy.result(current.contentKind, durationSec)
        reportCheckpoint(current, durationSec, force = true)
        reportCountDelta(current, durationSec)
        if (result.trackedDurationSec > 0L) {
            onSessionCompleted(
                CompletedShortformSession(
                    viewSessionId = current.viewSessionId,
                    platform = current.platform,
                    contentKey = current.contentKey,
                    contentKind = current.contentKind,
                    durationSec = result.trackedDurationSec,
                    count = result.count
                )
            )
        }
    }

    private fun reportCheckpoint(current: ActiveSession, durationSec: Long, force: Boolean = false) {
        val result = countingPolicy.result(current.contentKind, durationSec)
        if (result.trackedDurationSec <= current.checkpointDurationSec) return
        if (!force && current.checkpointDurationSec > 0L &&
            result.trackedDurationSec - current.checkpointDurationSec < 5L) return
        current.checkpointDurationSec = result.trackedDurationSec
        onCheckpoint(
            ShortformSessionCheckpoint(
                current.viewSessionId, current.platform, current.contentKey,
                current.contentKind, result.trackedDurationSec, result.count
            )
        )
    }

    private fun reportProvisional(current: ActiveSession, durationSec: Long, ended: Boolean = false) {
        if (countingPolicy.result(ShortformContentKind.NORMAL, durationSec).trackedDurationSec == 0L) return
        if (!ended && durationSec - current.checkpointDurationSec < 5L) return
        current.checkpointDurationSec = durationSec
        onProvisionalCheckpoint(ProvisionalViewCheckpoint(current.viewSessionId, current.platform, durationSec, ended))
    }

    private fun reportCountDelta(current: ActiveSession, durationSec: Long) {
        val totalCount = countingPolicy.result(current.contentKind, durationSec).count
        val delta = totalCount - current.reportedCount
        if (delta <= 0L) return
        current.reportedCount = totalCount
        onCountDelta(
            ShortformCountDelta(
                viewSessionId = current.viewSessionId,
                platform = current.platform,
                contentKey = current.contentKey,
                contentKind = current.contentKind,
                count = delta
            )
        )
    }

    private fun elapsedSec(current: ActiveSession): Long =
        ((current.pausedAtMs ?: clockMs()) - current.startedAtMs - current.pausedDurationMs)
            .coerceAtLeast(0L) / 1_000L
}
