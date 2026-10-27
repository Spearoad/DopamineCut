package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.manager.ScreenElement
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Resource aliases are hypotheses: verify on the target Instagram version before release. */
object InstagramUiRules {
    val players = setOf("clips_viewer_view_pager", "clips_viewer_viewpager", "clips_viewer_container", "clips_viewer_root")
    val creators = setOf("clips_author_username")
    val captions = setOf("clips_caption", "clips_caption_text", "clips_description")
    val captionRegions = setOf("clips_caption_component")
    val overlays = setOf("comments_container", "comments_panel", "share_sheet", "bottom_sheet_container", "bottom_sheet_container_view")
    fun id(element: ScreenElement) = element.viewId.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
    fun resource(snapshot: ScreenSnapshot, ids: Set<String>) = ids.any { snapshot.hasResourceId("com.instagram.android", it) }
}

class InstagramEntryDetector {
    fun detect(snapshot: ScreenSnapshot): ShortformScreenDetection {
        val structural = InstagramUiRules.resource(snapshot, InstagramUiRules.players)
        val overlayIds = InstagramUiRules.overlays + setOf(
            "comment_composer_parent_updated", "layout_comment_thread_edittext_multiline"
        )
        val matchedOverlayIds = snapshot.elements.map(InstagramUiRules::id).filter { it in overlayIds }.toSet()
        val dialogClasses = snapshot.elements.mapNotNull { it.className }
            .filter { it.endsWith("Dialog") || it.endsWith("BottomSheet") }.toSet()
        val overlay = matchedOverlayIds.isNotEmpty() || dialogClasses.isNotEmpty()
        val evidence = linkedSetOf<String>()
        if (structural) evidence += "instagram_player"
        matchedOverlayIds.forEach { evidence += "overlay_id:$it" }
        dialogClasses.forEach { evidence += "overlay_class:$it" }
        if (structural && overlay) return ShortformScreenDetection(ShortformScreenState.OVERLAY, .90f, evidence)
        if (structural) return ShortformScreenDetection(ShortformScreenState.INSIDE, .90f, evidence)
        // Merely seeing the Reels tab or a feed video must never qualify a viewing session.
        val marker = snapshot.texts.any { it.trim().lowercase(Locale.ROOT) in setOf("reels", "릴스") }
        return ShortformScreenDetection(if (marker || overlay) ShortformScreenState.CANDIDATE else ShortformScreenState.OUTSIDE,
            .50f, if (overlay) setOf("overlay_without_player") else if (marker) setOf("reels_marker_only") else emptySet())
    }
}

class InstagramIdentityExtractor {
    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun unique(snapshot: ScreenSnapshot, ids: Set<String>): String? {
        val values = snapshot.elements.filter { InstagramUiRules.id(it) in ids }.mapNotNull { e ->
            // Use actual text, not a parent contentDescription containing actions or counters.
            e.text?.let(::normalize)?.takeIf(String::isNotBlank)
        }.distinct()
        return values.singleOrNull()
    }

    /** Spatial association, not proof of parenthood: reject ambiguous captions. */
    private fun caption(snapshot: ScreenSnapshot): String? {
        val regions = snapshot.elements.filter { InstagramUiRules.id(it) in InstagramUiRules.captionRegions }
            .mapNotNull { it.bounds }.filter { it.width > 0f && it.height > 0f }.distinct()
        if (regions.size != 1) return unique(snapshot, InstagramUiRules.captions)
        val region = regions.single()
        val texts = snapshot.elements.asSequence().filter { element ->
            val b = element.bounds
            b != null && b.width > 0f && b.height > 0f &&
                    b.left >= region.left - .002f && b.right <= region.right + .002f &&
                    b.top >= region.top - .002f && b.bottom <= region.bottom + .002f &&
                    (element.viewId == null || InstagramUiRules.id(element) in InstagramUiRules.captions ||
                            InstagramUiRules.id(element) in InstagramUiRules.captionRegions)
        }.mapNotNull { element ->
            (element.contentDescription?.takeIf { it.isNotBlank() } ?: element.text)
                ?.let(::normalize)?.takeIf { it.isNotBlank() }
        }.distinct().toList()
        return texts.singleOrNull()
    }

    fun extract(snapshot: ScreenSnapshot): VideoIdentity {
        // Do not turn expanded caption/comment text into a new video identity.
        if (InstagramEntryDetector().detect(snapshot).state != ShortformScreenState.INSIDE) {
            return VideoIdentity(SupportedPlatform.INSTAGRAM, null, null, null, null, null, IdentityQuality.NONE, 0f)
        }
        val creator = unique(snapshot, InstagramUiRules.creators)
        val caption = caption(snapshot)
        // Expanded/truncated captions cannot safely supply a stable content identity.
        val truncated = caption?.let { it.endsWith("…") || it.endsWith("...") } == true
        // A preview is not a server media ID. Keep the uncertainty visible.
        val preview = caption?.replace(Regex("(?:\\.{3}|…)+$"), "")?.trim()?.takeIf { it.isNotBlank() }
        val key = if (creator != null && preview != null) {
            val canonical = "instagram:preview:v2:${creator.length}:$creator:${preview.length}:$preview"
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        } else null

        return VideoIdentity(SupportedPlatform.INSTAGRAM, key, creator, preview, null, null,
            if (key == null) IdentityQuality.NONE else IdentityQuality.LOW, if (key == null) 0f else .55f,
            identityTextTruncated = truncated)
    }

    fun isExplicitAd(snapshot: ScreenSnapshot): Boolean = snapshot.elements.any { e ->
        val id = InstagramUiRules.id(e)
        (id.contains("sponsor") || id.contains("ad_label")) && e.visibleTexts().any {
            normalize(it) in setOf("광고", "sponsored", "스폰서")
        }
    }
}

data class InstagramTrackingDecision(
    val retain: Boolean,
    val paused: Boolean,
    val contentKey: String?,
    val switched: Boolean = false,
    val reason: String
)

/** No wall clock, OCR, changing like counts, or full-screen text fingerprints. */
class InstagramTrackingCoordinator(
    private val settleMs: Long = 180L,
    private val evidenceGraceMs: Long = 2000L
) {
    private var active: String? = null
    private var pending: String? = null
    private var pendingSince = 0L
    private var lastReliable: Long? = null
    private var lastNow: Long? = null
    init { require(settleMs > 0 && evidenceGraceMs >= settleMs) }

    fun observe(state: ShortformScreenState, key: String?, explicitAd: Boolean, nowMs: Long): InstagramTrackingDecision {
        require(nowMs >= 0)
        if (lastNow?.let { nowMs < it } == true) reset()
        lastNow = nowMs
        if (explicitAd) { reset(); return InstagramTrackingDecision(false, true, null, reason = "EXPLICIT_AD") }
        if (state == ShortformScreenState.OVERLAY && active != null) {
            pending = null
            // Verified player overlay retains the ID without accumulating viewing time.
            lastReliable = nowMs
            return InstagramTrackingDecision(true, true, active, reason = "PLAYER_OVERLAY")
        }
        if (state != ShortformScreenState.INSIDE || key == null) {
            pending = null
            val withinGrace = lastReliable?.let { nowMs - it <= evidenceGraceMs } == true
            if (active != null && withinGrace) return InstagramTrackingDecision(true, true, active, reason = "EVIDENCE_GAP")
            reset()
            return InstagramTrackingDecision(false, true, null, reason = "EXIT_OR_UNIDENTIFIABLE")
        }
        if (key == active) {
            pending = null
            lastReliable = nowMs
            return InstagramTrackingDecision(true, false, active, reason = "SAME_VIDEO")
        }
        if (pending != key) {
            pending = key
            pendingSince = nowMs
            return InstagramTrackingDecision(active != null, true, active, reason = "SWITCH_PENDING")
        }
        if (nowMs - pendingSince < settleMs) return InstagramTrackingDecision(active != null, true, active, reason = "SETTLING")
        active = key
        pending = null
        lastReliable = nowMs
        return InstagramTrackingDecision(true, false, active, switched = true, reason = "STABLE_VIDEO")
    }
    fun reset() { active = null; pending = null; pendingSince = 0; lastReliable = null; lastNow = null }
}
