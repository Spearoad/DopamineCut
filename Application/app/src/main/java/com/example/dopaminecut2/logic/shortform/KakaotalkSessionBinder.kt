package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.domain.SupportedPlatform

/** 파이프라인 상태가 바뀔 때 서비스가 정리해야 할 것. */
data class KakaotalkStateChange(
    /** 진행 중인 캡처·OCR 작업을 무효화한다(댓글·공유창 진입·복귀 포함). */
    val newFrame: Boolean,
    /** 다른 영상으로 바뀌었거나 숏폼을 벗어났다. 이전 영상의 누적 OCR을 버린다. */
    val newVideo: Boolean
)

/**
 * [KakaotalkRecognitionPipeline] 상태를 시청 세션에 반영한다(진입 → 식별 → 분류 → 집계).
 * Android 의존성이 없어 전체 흐름을 단위 테스트로 검증한다.
 */
object KakaotalkSessionBinder {
    fun change(
        previousKey: String?,
        previousScreen: ShortformScreenState,
        state: KakaotalkRecognitionState
    ): KakaotalkStateChange {
        val newVideo = previousKey != state.contentKey
        // 같은 영상에서 댓글·공유창이 열리고 닫히는 것은 화면 변화일 뿐, 누적 OCR을 버릴 이유가 아니다.
        return KakaotalkStateChange(newFrame = newVideo || previousScreen != state.screen, newVideo = newVideo)
    }

    fun isShortform(state: KakaotalkRecognitionState): Boolean =
        state.screen == ShortformScreenState.INSIDE &&
            !state.paused && !state.excluded && state.kind == ShortformContentKind.NORMAL

    /** [interactionBlocked]는 승격 직후 목표 개입 화면이 떴는지 확인한다. */
    fun bind(
        tracker: ShortformSessionTracker,
        state: KakaotalkRecognitionState,
        newVideo: Boolean,
        interactionBlocked: () -> Boolean
    ) {
        if (newVideo) tracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
        when {
            state.contentKey == null || state.excluded || state.kind == ShortformContentKind.AD ->
                tracker.onScreenChanged(false, null, null, ShortformContentKind.UNKNOWN)
            state.paused -> tracker.setPaused(true)
            state.kind == ShortformContentKind.UNKNOWN -> {
                tracker.beginProvisional(SupportedPlatform.KAKAOTALK, state.contentKey)
                tracker.setPaused(false)
            }
            else -> {
                val provisionalId = tracker.activeViewSessionId()?.takeIf { tracker.isProvisional() }
                val promoted = provisionalId != null &&
                    tracker.resolveProvisional(provisionalId, state.kind, state.contentKey)
                if (!promoted) tracker.onScreenChanged(true, SupportedPlatform.KAKAOTALK,
                    state.contentKey, state.kind)
                // Promotion may synchronously show an intervention and pause this session.
                tracker.setPaused(interactionBlocked())
            }
        }
    }
}
