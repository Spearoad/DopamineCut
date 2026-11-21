package com.example.dopaminecut2.logic.shortform

import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.NormalizedBounds
import com.example.dopaminecut2.logic.manager.ScreenElement
import com.example.dopaminecut2.logic.manager.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실기기(SM-S916N, Android 16, 2026-11-21) KakaoUiDiagnostic 로그의 view ID 구성으로 댓글·공유창 판별을 확인한다. */
class KakaotalkOverlayDetectionTest {
    private val manager = KakaotalkManager()

    @Test
    fun `real comment sheet is an overlay even though it shows shortform words`() {
        val detection = manager.detectShortformScreen(commentSheet())
        assertEquals(ShortformScreenState.OVERLAY, detection.state)
        assertTrue("kakaotalk_overlay_structure" in detection.evidence)
    }

    @Test
    fun `real share sheet is an overlay`() {
        assertEquals(ShortformScreenState.OVERLAY, manager.detectShortformScreen(shareSheet()).state)
    }

    @Test
    fun `shortform player and chat list are not overlays`() {
        assertEquals(ShortformScreenState.INSIDE, manager.detectShortformScreen(player()).state)
        assertFalse(manager.detectShortformScreen(chatList()).state == ShortformScreenState.OVERLAY)
    }

    @Test
    fun `ad words inside a share sheet do not end the active video`() {
        manager.observeTracking(player(), 0L)
        manager.observeTracking(player(), 300L)

        val sheetOpen = manager.observeTracking(shareSheet(), 1_000L)
        val stillOpen = manager.observeTracking(shareSheet(), 14_000L)
        val back = manager.observeTracking(player(), 14_500L)

        assertEquals("PLAYER_OVERLAY", sheetOpen.reason)
        assertTrue(sheetOpen.retain && sheetOpen.paused)
        assertEquals("오래 열어 둬도 같은 영상 유지", "PLAYER_OVERLAY", stillOpen.reason)
        assertEquals("SAME_VIDEO", back.reason)
        assertEquals(sheetOpen.contentKey, back.contentKey)
    }

    @Test
    fun `explicit ad without an active video is still excluded`() {
        val decision = manager.observeTracking(shareSheet(), 0L)
        assertEquals("EXPLICIT_AD", decision.reason)
        assertFalse(decision.retain)
    }

    private fun player() = ScreenSnapshot(
        texts = listOf("숏폼", "좋아요", "댓글", "고양이 영상"), viewIds = emptyList(),
        elements = listOf(
            element("com.kakao.talk:id/main_tab_fragment_container", "android.widget.FrameLayout", 0f, 0f, 1f, .88f),
            element("com.kakao.talk:id/ktv_player_container", "android.widget.FrameLayout", 0f, 0f, 1f, .88f),
            element("com.kakao.talk:id/ktv_layout_bottom_custom_controller", "android.widget.RelativeLayout", 0f, .8f, 1f, .88f),
            element("com.kakao.talk:id/ktv_controller_seek_bar", "android.view.View", 0f, .86f, 1f, .87f)
        )
    )

    private fun commentSheet() = ScreenSnapshot(
        texts = listOf("숏폼", "댓글", "좋아요", "댓글 달기"), viewIds = emptyList(),
        elements = sheetFrame(.33f) + element(
            "com.kakao.talk.shortform_presentation:id/commentInputBarComposeView",
            "androidx.compose.ui.platform.ComposeView", 0f, .88f, 1f, 1f)
    )

    private fun shareSheet() = ScreenSnapshot(
        texts = listOf("공유", "채팅방 검색", "(광고) 이번 주 특가"), viewIds = emptyList(),
        elements = sheetFrame(.35f) + listOf(
            element("com.kakao.talk:id/search_input", "android.widget.EditText", .05f, .40f, .95f, .44f),
            element("com.kakao.talk:id/chat_room_list_item", "android.view.ViewGroup", 0f, .46f, 1f, .52f),
            element("com.kakao.talk:id/bottom_menu_list", "androidx.recyclerview.widget.RecyclerView", 0f, .83f, 1f, .95f)
        )
    )

    private fun chatList() = ScreenSnapshot(
        texts = listOf("채팅", "숏폼 공유했어요", "좋아요"), viewIds = emptyList(),
        elements = listOf(
            element("com.kakao.talk:id/chat_roomLayout", "android.view.ViewGroup", 0f, .2f, 1f, .27f),
            element("com.kakao.talk:id/recycler_view", "androidx.recyclerview.widget.RecyclerView", 0f, .15f, 1f, .9f)
        )
    )

    private fun sheetFrame(top: Float) = listOf(
        element("com.kakao.talk:id/coordinator", "android.view.ViewGroup", 0f, .04f, 1f, 1f),
        element("com.kakao.talk:id/touch_outside", "android.view.View", 0f, .04f, 1f, 1f),
        element("com.kakao.talk:id/design_bottom_sheet", "android.widget.FrameLayout", 0f, top, 1f, 1f),
        element("com.kakao.talk:id/handle", "android.view.View", .455f, top + .009f, .543f, top + .014f)
    )

    private fun element(id: String, className: String, l: Float, t: Float, r: Float, b: Float) =
        ScreenElement(text = null, contentDescription = null, viewId = id, className = className,
            bounds = NormalizedBounds(l, t, r, b), isSelected = false)
}
