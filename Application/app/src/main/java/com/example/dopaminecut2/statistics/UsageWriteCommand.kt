package com.example.dopaminecut2.statistics

import com.example.dopaminecut2.data.local.InterventionLedger
import com.example.dopaminecut2.data.local.PendingRecognition
import com.example.dopaminecut2.domain.SupportedPlatform
import com.example.dopaminecut2.logic.shortform.ShortformContentKind

/** Immutable owned records only: never capture the service, a Bitmap or OCR text in the queue. */
sealed interface UsageWriteCommand {
    val userId: String
    val operation: String
    data class Checkpoint(override val userId: String, val date: String, val sessionId: String,
        val platform: SupportedPlatform, val durationSec: Long, val count: Long, val occurredAtMs: Long,
        val newTimeSec: Long, val before21: Boolean, val kind: ShortformContentKind) : UsageWriteCommand {
        override val operation = "시청량 저장"
    }
    data class AppTime(override val userId: String, val date: String, val platform: SupportedPlatform,
        val seconds: Long) : UsageWriteCommand { override val operation = "앱 사용 시간 저장" }
    data class Pending(val record: PendingRecognition) : UsageWriteCommand {
        override val userId get() = record.userId
        override val operation = "미확인 시청 기록 저장"
    }
    data class SettlePending(override val userId: String, val sessionId: String) : UsageWriteCommand {
        override val operation = "미확인 시청 기록 정리"
    }
    data class Category(override val userId: String, val date: String, val sessionId: String,
        val classification: UsageClassification) : UsageWriteCommand { override val operation = "콘텐츠 분류 저장" }
    data class Ledger(override val userId: String, val date: String, val ledger: InterventionLedger) : UsageWriteCommand {
        override val operation = "개입 이력 저장"
    }
    data class Heartbeat(override val userId: String, val date: String, val seconds: Long,
        val before21: Boolean, val occurredAtMs: Long) : UsageWriteCommand { override val operation = "측정 품질 저장" }
}
