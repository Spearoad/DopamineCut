package com.example.dopaminecut2.logic.shortform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaotalkScreenDecisionTest {
    private val thresholds = KakaotalkScreenDecision.MODEL_CLASSES.associateWith { 0.7f }

    @Test
    fun `confident shortform is counted as normal content`() {
        val prediction = KakaotalkScreenDecision.decide(floatArrayOf(6f, 1f, 0f, 0f), thresholds)
        assertEquals(KakaotalkUiClass.SHORTFORM_NORMAL, prediction.uiClass)
        assertEquals(ShortformContentKind.NORMAL, prediction.contentKind)
        assertEquals(1f, prediction.probabilities.values.sum(), 1e-5f)
    }

    @Test
    fun `confident ad is excluded as ad`() {
        val prediction = KakaotalkScreenDecision.decide(floatArrayOf(0f, 6f, 0f, 0f), thresholds)
        assertEquals(KakaotalkUiClass.SHORTFORM_AD, prediction.uiClass)
        assertEquals(ShortformContentKind.AD, prediction.contentKind)
    }

    @Test
    fun `low confidence falls back to unknown`() {
        val prediction = KakaotalkScreenDecision.decide(floatArrayOf(1f, 0.9f, 0.8f, 0f), thresholds)
        assertEquals(KakaotalkUiClass.UNKNOWN, prediction.uiClass)
        assertEquals(ShortformContentKind.UNKNOWN, prediction.contentKind)
        assertTrue(prediction.confidence < 0.7f)
    }

    @Test
    fun `non shortform screens are never counted`() {
        listOf(2 to KakaotalkUiClass.NOW_OPENCHAT, 3 to KakaotalkUiClass.CHAT_FRIENDS)
            .forEach { (index, expected) ->
                val logits = FloatArray(4).also { it[index] = 8f }
                val prediction = KakaotalkScreenDecision.decide(logits, thresholds)
                assertEquals(expected, prediction.uiClass)
                assertTrue(prediction.uiClass.isNonShortformScreen)
                assertEquals(ShortformContentKind.UNKNOWN, prediction.contentKind)
            }
        assertFalse(KakaotalkUiClass.SHORTFORM_NORMAL.isNonShortformScreen)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non finite logits are rejected`() {
        KakaotalkScreenDecision.decide(floatArrayOf(Float.NaN, 0f, 0f, 0f), thresholds)
    }
}
