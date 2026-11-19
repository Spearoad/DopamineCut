package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.ScreenSnapshot

class YoutubeEntryDetector {
    fun detect(snapshot: ScreenSnapshot, hasShortformContext: Boolean = false): ShortformScreenDetection {
        val evidence = linkedSetOf<String>()
        val hasRoot = snapshot.hasResourceId(PACKAGE_NAME, "reel_watch_fragment_root")
        val hasPageContainer = snapshot.hasResourceId(PACKAGE_NAME, "reel_player_page_container")
        val hasReelRecycler = snapshot.hasResourceId(PACKAGE_NAME, "reel_recycler")
        val hasStructuralPlayer = hasPageContainer && hasReelRecycler
        val hasPlayer = hasRoot || hasStructuralPlayer
        // The app's share dialog replaces the accessibility root: no background player IDs remain.
        val hasShareSheet = snapshot.hasResourceId(PACKAGE_NAME, "design_bottom_sheet") &&
            snapshot.hasResourceId(PACKAGE_NAME, "bottom_sheet_list") &&
            snapshot.hasResourceId(PACKAGE_NAME, "touch_outside")
        // ID가 바뀌어도 설명 단서는 독립적인 후보를 만든다. 후보는 집계 확정이 아니다.
        val semanticGroups = listOf(
            listOf("채널로 이동", "go to channel"),
            listOf("이 사운드를 사용하는 동영상", "use this sound"),
            listOf("리믹스", "remix"),
            listOf("동영상 공유", "share")
        ).count { group -> snapshot.texts.any { text -> group.any { text.contains(it, ignoreCase = true) } } }
        val hasShortsOverlay = snapshot.hasResourceId(PACKAGE_NAME, "app_engagement_panel") &&
            snapshot.hasResourceId(PACKAGE_NAME, "panel_content_touch_wrapper") &&
            snapshot.hasResourceId(PACKAGE_NAME, "reel_time_bar")
        val hasMarker = snapshot.texts.any { text ->
            SECTION_MARKERS.any { text.equals(it, ignoreCase = true) }
        }
        val hasOverlay = snapshot.texts.any { text ->
            OVERLAY_MARKERS.any { text.equals(it, ignoreCase = true) }
        } || snapshot.elements.any { element ->
            element.className?.let { it.endsWith("Dialog") || it.endsWith("BottomSheet") } == true
        }

        if (hasRoot) evidence += "player_root"
        if (hasPageContainer) evidence += "player_page_container"
        if (hasReelRecycler) evidence += "reel_recycler"
        if (hasShortsOverlay) evidence += "shorts_engagement_panel"
        if (hasShareSheet) evidence += "share_sheet"
        if (hasMarker) evidence += "section_marker"
        if (hasOverlay) evidence += "overlay"
        if (semanticGroups >= 3) evidence += "semantic_player_candidate"

        return when {
            hasShareSheet && (hasShortformContext || hasPlayer) -> ShortformScreenDetection(
                ShortformScreenState.OVERLAY,
                0.99f,
                evidence
            )

            hasShortsOverlay -> ShortformScreenDetection(
                ShortformScreenState.OVERLAY,
                0.99f,
                evidence
            )

            hasPlayer && hasOverlay -> ShortformScreenDetection(
                ShortformScreenState.OVERLAY,
                0.99f,
                evidence
            )

            hasPlayer -> ShortformScreenDetection(
                ShortformScreenState.INSIDE,
                0.98f,
                evidence
            )

            hasMarker || semanticGroups >= 3 -> ShortformScreenDetection(
                ShortformScreenState.CANDIDATE,
                0.55f,
                evidence
            )

            else -> ShortformScreenDetection(
                ShortformScreenState.OUTSIDE,
                0.95f,
                evidence
            )
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.google.android.youtube"
        val SECTION_MARKERS = listOf("shorts", "쇼츠", "reel")
        val OVERLAY_MARKERS = listOf(
            "댓글 패널",
            "comments panel",
            "공유 대상",
            "share sheet"
        )
    }
}
