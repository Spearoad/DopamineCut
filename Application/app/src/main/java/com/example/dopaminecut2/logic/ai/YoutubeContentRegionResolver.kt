package com.example.dopaminecut2.logic.ai

import com.example.dopaminecut2.logic.manager.NormalizedBounds
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import com.example.dopaminecut2.logic.shortform.YoutubeAccessibilityIdentityExtractor

/** 확인된 세로 플레이어만 허용한다. 폴더블의 넓은 컨테이너를 실제 영상으로 추측하지 않는다. */
class YoutubeContentRegionResolver {
    fun player(snapshot: ScreenSnapshot): NormalizedBounds? {
        for (id in PLAYER_IDS) {
            val candidates = snapshot.elements.filter { it.viewId?.substringAfterLast('/') == id }
                .mapNotNull { it.bounds }.distinct()
            if (candidates.size > 1) return null // 전환 중 두 페이지가 겹침
            if (candidates.size == 1) return candidates.single()
        }
        return null
    }

    fun resolve(frame: OcrFrame, snapshot: ScreenSnapshot): NormalizedBounds? {
        if (snapshot.displayId != 0) return null // 현재 캡처는 기본 디스플레이만 지원
        val window = snapshot.window ?: return null
        val player = window.toCapture(player(snapshot) ?: return null, frame.width, frame.height) ?: return null
        val aspect = player.width * frame.width / (player.height * frame.height)
        if (aspect !in 0.30f..0.85f) return null
        val creator = YoutubeAccessibilityIdentityExtractor().creatorBounds(snapshot) ?: return null
        val creatorBounds = window.toCapture(creator, frame.width, frame.height) ?: return null
        // 옆 패널의 작성자를 영상 하단 경계로 사용하지 않는다.
        if (!creatorBounds.centerIsInside(player)) return null
        val top = player.top + player.height * .10f
        // Keep a 7% player-height margin above the creator row.
        val bottom = creatorBounds.top - player.height * .07f
        if (bottom <= top || bottom > player.bottom) return null
        return NormalizedBounds(player.left, top, player.right, bottom)
    }

    fun layoutKey(snapshot: ScreenSnapshot): String =
        "${snapshot.displayId}:${snapshot.window}:${player(snapshot)}"

    /** Layout revalidation preserves an existing session; it must not block initial identity extraction. */
    fun requiresRevalidation(previous: String?, next: String, activeContentKey: String?): Boolean =
        previous != null && previous != next && activeContentKey != null

    companion object {
        // 실제 수집 트리에서 확인한 영역 ID. 없으면 전체 화면으로 폴백하지 않는다.
        private val PLAYER_IDS = listOf("reel_watch_player", "reel_player_underlay", "reel_player_page_content")
    }
}
