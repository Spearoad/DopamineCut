package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.ShortformScreenDetection
import com.example.dopaminecut2.logic.shortform.ShortformScreenState
import com.example.dopaminecut2.logic.shortform.IdentityQuality
import com.example.dopaminecut2.logic.shortform.VideoIdentity

interface AppManagerInterface {
    val platform: SupportedPlatform
    val packageName: String get() = platform.packageName

    /** 현재 화면이 숏폼(쇼츠/릴스/틱톡)인지 판별 */
    fun isShortformSection(snapshot: ScreenSnapshot): Boolean

    /** 진입 판정 근거와 불명확 상태를 보존한다. */
    fun detectShortformScreen(snapshot: ScreenSnapshot): ShortformScreenDetection {
        val isShortform = isShortformSection(snapshot)
        return ShortformScreenDetection(
            state = if (isShortform) ShortformScreenState.INSIDE else ShortformScreenState.OUTSIDE,
            confidence = if (isShortform) 0.70f else 0.80f,
            evidence = setOf("legacy_rule")
        )
    }

    /** 영상 고유 식별자 추출 (중복 시청 방지용) */
    fun getVideoIdentifier(snapshot: ScreenSnapshot): String?

    fun getVideoIdentity(snapshot: ScreenSnapshot, observedAtElapsedMs: Long): VideoIdentity? {
        val contentKey = getVideoIdentifier(snapshot) ?: return null
        return VideoIdentity(
            platform = platform,
            contentKey = contentKey,
            creator = null,
            title = null,
            audio = null,
            titleVisualHash = null,
            quality = IdentityQuality.LOW,
            confidence = 0.55f
        )
    }

    /** 현재 화면의 광고 식별 */
    fun isAdContent(snapshot: ScreenSnapshot): Boolean

    /** Choose the next content step; each platform owns its policy, not the service. */
    fun contentTrackingCommand(snapshot: ScreenSnapshot, context: ContentTrackingContext): ContentTrackingCommand

}
