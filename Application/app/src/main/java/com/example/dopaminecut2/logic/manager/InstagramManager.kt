package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.*

/** Stateful only for observeTracking; the ordinary detection methods are pure. */
class InstagramManager : BaseAppManager() {
    override val platform = SupportedPlatform.INSTAGRAM
    private val detector = InstagramEntryDetector()
    private val extractor = InstagramIdentityExtractor()
    private val coordinator = InstagramTrackingCoordinator()

    override fun contentTrackingCommand(snapshot: ScreenSnapshot, context: ContentTrackingContext): ContentTrackingCommand =
        when {
            isAdContent(snapshot) -> ContentTrackingCommand.Track(ShortformContentKind.AD, cancelInference = true)
            context.contentKey != context.activeKey -> ContentTrackingCommand.BeginClassification()
            context.knownNonReel -> ContentTrackingCommand.Wait
            context.kind != ShortformContentKind.UNKNOWN -> ContentTrackingCommand.Track(context.kind)
            !context.inferenceRunning -> ContentTrackingCommand.RetryClassification
            else -> ContentTrackingCommand.Wait
        }

    override fun detectShortformScreen(snapshot: ScreenSnapshot) = detector.detect(snapshot)
    override fun isShortformSection(snapshot: ScreenSnapshot) = detector.detect(snapshot).isConfirmedShortform
    override fun getVideoIdentifier(snapshot: ScreenSnapshot) = extractor.extract(snapshot).contentKey
    override fun getVideoIdentity(snapshot: ScreenSnapshot, observedAtElapsedMs: Long) = extractor.extract(snapshot)
    override fun isAdContent(snapshot: ScreenSnapshot) = extractor.isExplicitAd(snapshot)

    fun observeTracking(snapshot: ScreenSnapshot, nowMs: Long): InstagramTrackingDecision {
        // InstagramDetection.detect() 함수 시그니처 변경으로 인한 코드 추가 (hasActiveReel 추가)
        val detection = detector.detect(
            snapshot,
            hasActiveReel = coordinator.hasActiveReel
        )
        val identity = if (detection.state == ShortformScreenState.INSIDE) extractor.extract(snapshot) else null
        return coordinator.observe(detection.state, identity?.contentKey, isAdContent(snapshot), nowMs)
    }

    fun resetTracking() = coordinator.reset()
}
