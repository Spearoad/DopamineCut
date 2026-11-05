package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.KakaotalkTrackingCoordinator
import com.example.dopaminecut2.logic.shortform.KakaotalkTrackingDecision
import com.example.dopaminecut2.logic.shortform.ShortformScreenDetection
import com.example.dopaminecut2.logic.shortform.ShortformScreenState
import java.text.Normalizer
import java.util.Locale

class KakaotalkManager : BaseAppManager() {

    override val platform = SupportedPlatform.KAKAOTALK
    private val trackingCoordinator = KakaotalkTrackingCoordinator()

    override fun detectShortformScreen(snapshot: ScreenSnapshot): ShortformScreenDetection =
        trackingCoordinator.detect(snapshot)

    override fun isShortformSection(snapshot: ScreenSnapshot): Boolean {
        return detectShortformScreen(snapshot).state == ShortformScreenState.INSIDE
    }

    override fun getVideoIdentifier(snapshot: ScreenSnapshot): String? {
        // 카운터처럼 영상이 바뀌지 않아도 자주 변하는 접근성 텍스트를
        // 영상 식별자에 넣지 않는다. 화면 원문은 저장하지 않고 해시만 반환한다.
        val stableTexts = snapshot.texts.asSequence()
            .map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
            .filter(String::isNotEmpty)
            .filterNot { text ->
                (staticControlKeywords + shortformMarkers).any { it.equals(text, ignoreCase = true) } ||
                    dynamicCounter.matches(text) ||
                    actionWithCounter.matches(text)
            }
            .map { it.lowercase(Locale.ROOT).replace(whitespace, " ") }
            .distinct()
            .toList()
        return ScreenSnapshot(stableTexts, emptyList()).contentFingerprint(emptyList())
    }

    override fun isAdContent(snapshot: ScreenSnapshot): Boolean {
        return snapshot.containsAny(adKeywords)
    }

    fun observeTracking(snapshot: ScreenSnapshot, nowMs: Long): KakaotalkTrackingDecision =
        trackingCoordinator.observe(snapshot, getVideoIdentifier(snapshot), isAdContent(snapshot), nowMs)

    fun resetTracking() = trackingCoordinator.reset()

    private companion object {
        val shortformMarkers = listOf("쇼츠", "shorts", "shortform", "숏폼")
        // 숫자만 있는 카운터, 재생 시간, 또는 "좋아요 1.2만" 같은 접근성 문자열.
        val dynamicCounter = Regex(
            """^(?:\d+(?:[.,]\d+)?(?:천|만|억|k|m)?|(?:\d{1,2}:){1,2}\d{2}|\d+초(?:\s*중\s*\d+초)?)$""",
            RegexOption.IGNORE_CASE
        )
        val actionWithCounter = Regex(
            """^(?:좋아요|댓글|공유|저장|구독|like|comment|share|save|subscribe)\s*[:·]?\s*\d+(?:[.,]\d+)?(?:천|만|억|k|m)?(?:\s*(?:개|명|회|likes?|comments?|shares?))?$""",
            RegexOption.IGNORE_CASE
        )
        val whitespace = Regex("\\s+")
    }
}
