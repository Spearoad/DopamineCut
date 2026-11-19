package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.*

/** Stateful only for observeTracking; the ordinary detection methods are pure. */
class InstagramManager : BaseAppManager() {
    override val platform = SupportedPlatform.INSTAGRAM
    private val detector = InstagramEntryDetector()
    private val extractor = InstagramIdentityExtractor()
    private val coordinator = InstagramTrackingCoordinator()
    private val liveMarkerIds = emptySet<String>()
    // 사진콘텐츠 구분을 위한 코드 추가
    private val photoPostMarkerIds = setOf("clips_carousel_image_media_content")
    private val photoCarouselIds = setOf("clips_carousel_viewpager")

    // 사진콘텐츠를 인식하기 위해 함수 내용 변경
    private fun detectTimeOnlyKind(snapshot: ScreenSnapshot): ShortformContentKind? {
        val isLive = InstagramUiRules.resource(snapshot, liveMarkerIds)
        val isPhoto = InstagramUiRules.resource(snapshot, photoCarouselIds) &&
                snapshot.elements.any { element ->
                    val bounds = element.bounds
                    InstagramUiRules.id(element) in photoPostMarkerIds &&
                            element.className == "android.widget.ImageView" &&
                            bounds != null &&
                            bounds.width >= 0.5f &&
                            bounds.height >= 0.25f
                }

        return when {
            isLive && !isPhoto -> ShortformContentKind.LIVE
            isPhoto && !isLive -> ShortformContentKind.PHOTO_POST
            else -> null
        }
    }

    // 사진콘텐츠 감지를 위해 코드 수정
    override fun contentTrackingCommand(
        snapshot: ScreenSnapshot,
        context: ContentTrackingContext
    ): ContentTrackingCommand {
        if (isAdContent(snapshot)) {
            return ContentTrackingCommand.Track(
                ShortformContentKind.AD,
                cancelInference = true
            )
        }

        val timeOnlyKind = detectTimeOnlyKind(snapshot)
        if (timeOnlyKind != null) {
            return ContentTrackingCommand.Track(timeOnlyKind, cancelInference = true)
        }

        return when {
            context.contentKey != context.activeKey ->
                ContentTrackingCommand.BeginClassification()
            context.knownNonReel ->
                ContentTrackingCommand.Wait
            context.kind != ShortformContentKind.UNKNOWN ->
                ContentTrackingCommand.Track(context.kind)
            !context.inferenceRunning ->
                ContentTrackingCommand.RetryClassification
            else ->
                ContentTrackingCommand.Wait
        }
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
