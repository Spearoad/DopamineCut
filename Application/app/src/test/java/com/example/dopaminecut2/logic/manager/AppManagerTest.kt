package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.logic.shortform.ShortformScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppManagerTest {
    @Test
    fun `youtube requires a player resource and rejects navigation text alone`() {
        val manager = YoutubeManager()
        assertTrue(
            manager.isShortformSection(
                snapshot(
                    texts = arrayOf("고양이 영상"),
                    viewIds = arrayOf("com.google.android.youtube:id/reel_watch_fragment_root")
                )
            )
        )
        assertEquals(
            ShortformScreenState.CANDIDATE,
            manager.detectShortformScreen(snapshot("Shorts", "좋아요")).state
        )
        assertFalse(manager.isShortformSection(snapshot("Shorts", "좋아요", "댓글", "공유")))
        assertFalse(manager.isShortformSection(snapshot("Shorts", "홈")))
    }

    @Test
    fun `instagram and kakaotalk need feed marker and action`() {
        assertTrue(InstagramManager().isShortformSection(snapshot("릴스", "댓글", "댄스 영상")))
        assertFalse(InstagramManager().isShortformSection(snapshot("릴스")))
        assertTrue(KakaotalkManager().isShortformSection(snapshot("숏폼", "좋아요", "친구 영상")))
    }

    @Test
    fun `kakaotalk identity ignores changing controls counters and playback time`() {
        val manager = KakaotalkManager()
        val first = manager.getVideoIdentifier(
            snapshot("숏폼", "@creator", "재미있는 고양이 영상", "좋아요 1.2만", "댓글 32", "00:12")
        )
        val sameVideo = manager.getVideoIdentifier(
            snapshot("숏폼", "@creator", "재미있는 고양이 영상", "좋아요 1.3만", "댓글 41", "00:18")
        )
        val nextVideo = manager.getVideoIdentifier(
            snapshot("숏폼", "@creator", "주말 여행 브이로그", "좋아요 1.3만", "댓글 41", "00:03")
        )

        assertNotNull(first)
        assertEquals(first, sameVideo)
        assertNotEquals(first, nextVideo)
    }

    @Test
    fun `kakaotalk controls alone do not make a content identity`() {
        val identifier = KakaotalkManager().getVideoIdentifier(
            snapshot("숏폼", "좋아요", "댓글", "공유", "1.2만", "00:12")
        )

        assertEquals(null, identifier)
    }

    @Test
    fun `youtube author and title identity is stable and changes with content`() {
        val manager = YoutubeManager()
        val first = manager.getVideoIdentifier(metadataSnapshot("첫 번째 고양이 영상"))
        val same = manager.getVideoIdentifier(metadataSnapshot("첫 번째 고양이 영상"))
        val second = manager.getVideoIdentifier(metadataSnapshot("두 번째 여행 영상"))

        assertNotNull(first)
        assertTrue(first == same)
        assertNotEquals(first, second)
    }

    private fun metadataSnapshot(title: String) = ScreenSnapshot(
        texts = listOf("@channel", title),
        viewIds = emptyList(),
        elements = listOf(
            ScreenElement("@channel", null, null, null,
                NormalizedBounds(0.05f, 0.72f, 0.70f, 0.76f), false),
            ScreenElement(title, null, null, null,
                NormalizedBounds(0.05f, 0.80f, 0.70f, 0.84f), false)
        )
    )

    private fun snapshot(vararg texts: String) = ScreenSnapshot(texts.toList(), emptyList())

    private fun snapshot(
        texts: Array<String> = emptyArray(),
        viewIds: Array<String> = emptyArray()
    ) = ScreenSnapshot(texts.toList(), viewIds.toList())
}
