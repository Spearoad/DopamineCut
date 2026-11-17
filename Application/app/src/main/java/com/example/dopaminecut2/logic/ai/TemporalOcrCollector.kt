package com.example.dopaminecut2.logic.ai

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.delay

/** Bounded, RAM-only subtitle buffer. Never participates in video identity generation. */
class OcrTextAccumulator(private val maxChars: Int = ContentSamplingPolicy.MAX_TEXT_CHARS) {
    private val segments = mutableListOf<String>()
    private val buffer = StringBuilder(maxChars)
    init { require(maxChars > 0) }
    val text: String get() = buffer.toString()
    val isFull: Boolean get() = buffer.length >= maxChars

    fun add(raw: String) {
        if (isFull) return
        raw.lineSequence().forEach { line ->
            val candidate = line.replace(Regex("\\s+"), " ").trim().take(maxChars)
            val key = normalized(candidate)
            if (key.isEmpty() || key in UI_ONLY) return@forEach
            val existingKeys = segments.map(::normalized)
            if (existingKeys.any { it.contains(key) }) return@forEach
            val extensions = existingKeys.indices.filter { key.contains(existingKeys[it]) }
            if (extensions.isNotEmpty()) {
                // A growing subtitle replaces its shorter partial recognition, not the whole buffer.
                val first = extensions.first()
                segments[first] = candidate
                extensions.drop(1).asReversed().forEach(segments::removeAt)
            } else {
                segments += candidate
            }
            buffer.clear()
            for (segment in segments) {
                if (buffer.length >= maxChars) break
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(segment.take(maxChars - buffer.length))
            }
            // Keep storage bounded as well as the eventual request text.
            if (isFull) {
                segments.clear()
                segments += text
                return
            }
        }
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private companion object {
        val UI_ONLY = setOf("autodubbed", "자동더빙")
    }
}

/** Snapshot ownership is checked on BOTH sides of every suspending capture. */
class TemporalOcrCollector(
    private val now: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val intervalMs: Long = ContentSamplingPolicy.INTERVAL_MS
) {
    init { require(intervalMs > 0) }

    suspend fun collect(
        isCurrent: () -> Boolean,
        canCapture: () -> Boolean = { true },
        capture: suspend () -> String?
    ): String {
        val buffer = OcrTextAccumulator()
        while (isCurrent()) {
            // A full buffer waits for the same session to end, without any more OCR.
            // Temporary comments/share overlays also pause sampling without discarding text.
            if (buffer.isFull || !canCapture()) {
                wait(250L)
                continue
            }
            val sampleStarted = now()
            val sample = capture()
            if (!isCurrent()) break // Late OCR from a transition must never enter the old/new session.
            if (!canCapture()) continue
            sample?.let(buffer::add)
            // Short interruptible checks finalize completed sessions without waiting a full sample period.
            val next = sampleStarted + intervalMs
            while (isCurrent() && now() < next && !buffer.isFull) {
                wait(minOf(250L, next - now()).coerceAtLeast(1L))
            }
        }
        return buffer.text
    }
}
