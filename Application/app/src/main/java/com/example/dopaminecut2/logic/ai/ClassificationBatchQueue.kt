package com.example.dopaminecut2.logic.ai

/**
 * 기본 동작은 OCR 원문을 디스크에 남기지 않고 메모리에서 최대 5개까지 모은다.
 * 명시적으로 활성화한 짧은 개발 진단에서는 전송 본문이 로컬 로그에 기록될 수 있다.
 * 중복 세션은 최신 텍스트로 교체하며, 오래된 항목은 시간 기준으로 부분 전송한다.
 */
class ClassificationBatchQueue(
    private val maxBatchSize: Int = 5,
    private val maxWaitMs: Long = 15_000L,
    private val maxTextLength: Int = 500
) {
    private val pending = linkedMapOf<String, PendingContentClassification>()

    init {
        require(maxBatchSize > 0)
        require(maxWaitMs > 0L)
        require(maxTextLength > 0)
    }

    @Synchronized
    fun offer(item: PendingContentClassification): ContentClassificationBatch? {
        val previous = pending[item.viewSessionId]
        val sanitized = item.copy(
            ocrText = item.ocrText
                .replace(WHITESPACE, " ")
                .trim()
                .take(maxTextLength),
            // Repeated OCR improves the text, but must not postpone the original
            // batch deadline forever.
            enqueuedAtElapsedMs = previous?.enqueuedAtElapsedMs ?: item.enqueuedAtElapsedMs
        )
        if (sanitized.ocrText.isBlank()) return null
        pending[sanitized.viewSessionId] = sanitized
        return if (pending.size >= maxBatchSize) drain(BatchFlushReason.FULL) else null
    }

    @Synchronized
    fun pollDue(nowElapsedMs: Long): ContentClassificationBatch? {
        val oldest = pending.values.firstOrNull() ?: return null
        return if (nowElapsedMs - oldest.enqueuedAtElapsedMs >= maxWaitMs) {
            drain(BatchFlushReason.TIMEOUT)
        } else {
            null
        }
    }

    @Synchronized
    fun flush(): ContentClassificationBatch? = drain(BatchFlushReason.MANUAL)

    @Synchronized
    fun size(): Int = pending.size

    private fun drain(reason: BatchFlushReason): ContentClassificationBatch? {
        if (pending.isEmpty()) return null
        val batch = ContentClassificationBatch(pending.values.toList(), reason)
        pending.clear()
        return batch
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
