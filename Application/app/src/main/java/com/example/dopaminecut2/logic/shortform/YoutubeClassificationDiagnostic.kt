package com.example.dopaminecut2.logic.shortform

import java.util.Locale

/** Structured diagnostics only; callers must gate Android logging with BuildConfig.DEBUG. */
object YoutubeClassificationDiagnostic {
    fun format(
        prediction: ShortformContentPrediction?,
        resolved: ShortformContentKind?,
        confirmed: ShortformContentKind?,
        reason: String,
        elapsedMs: Long
    ): String {
        val top = prediction?.probabilities?.maxByOrNull { it.value }
        val probabilities = listOf(ShortformContentKind.NORMAL, ShortformContentKind.AD,
            ShortformContentKind.LIVE, ShortformContentKind.PHOTO_POST).joinToString(",") { kind ->
            "$kind=" + (prediction?.probabilities?.get(kind)?.let {
                String.format(Locale.ROOT, "%.3f", it)
            } ?: "NA")
        }
        return "platform=YOUTUBE top=${top?.key ?: "NA"} " +
            "model=${prediction?.kind ?: "ERROR"} resolved=${resolved ?: "NA"} " +
            "confirmed=${confirmed ?: "NA"} reason=$reason " +
            "latencyMs=${elapsedMs.coerceAtLeast(0)} scores=[$probabilities]"
    }
}
