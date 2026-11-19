package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.ShortformScreenDetection
import com.example.dopaminecut2.logic.shortform.ShortformContentKind
import com.example.dopaminecut2.logic.shortform.VideoIdentity
import com.example.dopaminecut2.logic.shortform.YoutubeAccessibilityIdentityExtractor
import com.example.dopaminecut2.logic.shortform.YoutubeEntryDetector

class YoutubeManager : BaseAppManager() {

    override val platform = SupportedPlatform.YOUTUBE
    private val entryDetector = YoutubeEntryDetector()
    private val identityExtractor = YoutubeAccessibilityIdentityExtractor()

    override fun contentTrackingCommand(snapshot: ScreenSnapshot, context: ContentTrackingContext): ContentTrackingCommand =
        when {
            context.contentKey != context.activeKey -> ContentTrackingCommand.BeginClassification(provisional = true)
            context.kind != ShortformContentKind.UNKNOWN ->
                ContentTrackingCommand.Track(context.kind)
            else -> ContentTrackingCommand.Wait // Existing YouTube ticker owns provisional/live retries.
        }

    override fun isShortformSection(snapshot: ScreenSnapshot): Boolean {
        return detectShortformScreen(snapshot).isConfirmedShortform
    }

    override fun detectShortformScreen(snapshot: ScreenSnapshot): ShortformScreenDetection =
        entryDetector.detect(snapshot)

    fun detectShortformScreen(snapshot: ScreenSnapshot, hasShortformContext: Boolean): ShortformScreenDetection =
        entryDetector.detect(snapshot, hasShortformContext)

    override fun isAdContent(snapshot: ScreenSnapshot): Boolean {
        return com.example.dopaminecut2.logic.shortform.YoutubeAdEvidenceDetector.detect(snapshot).explicitAd
    }

    override fun getVideoIdentifier(snapshot: ScreenSnapshot): String? {
        return getVideoIdentity(snapshot, observedAtElapsedMs = 0L)?.contentKey
    }

    override fun getVideoIdentity(
        snapshot: ScreenSnapshot,
        observedAtElapsedMs: Long
    ): VideoIdentity = identityExtractor.extract(snapshot, observedAtElapsedMs)
}
