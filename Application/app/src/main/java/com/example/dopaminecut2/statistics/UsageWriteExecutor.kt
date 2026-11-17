package com.example.dopaminecut2.statistics

import com.example.dopaminecut2.data.local.HabitStore
import com.example.dopaminecut2.data.local.InterventionLedgerStore
import com.example.dopaminecut2.data.local.PendingRecognitionStore
import com.example.dopaminecut2.domain.ContentCategory
import com.example.dopaminecut2.logic.shortform.ShortformContentKind

/** Existing storage APIs are reused. Commands are sequential, not automatically replayed after partial failures. */
class UsageWriteExecutor(private val statistics: UsageStatisticsManager, private val habits: HabitStore,
    private val pending: PendingRecognitionStore, private val ledger: InterventionLedgerStore) {
    suspend fun execute(command: UsageWriteCommand) {
        when (command) {
            is UsageWriteCommand.Checkpoint -> {
                habits.addTime(command.userId, command.date, command.newTimeSec, command.before21)
                statistics.checkpointShortformSession(command.userId, command.date, command.sessionId,
                    command.platform, command.durationSec, command.count, command.occurredAtMs,
                    if (command.kind == ShortformContentKind.LIVE) ContentCategory.LIVE else ContentCategory.UNKNOWN).getOrThrow()
                if (command.kind == ShortformContentKind.PHOTO_POST) statistics.resolveShortformCategory(
                    command.userId, command.date, command.sessionId,
                    UsageClassification(ContentCategory.UNKNOWN, "UNRESOLVED", "ON_DEVICE", reason = "PHOTO_POST_TIME_ONLY")).getOrThrow()
            }
            is UsageWriteCommand.AppTime -> statistics.recordRunTime(command.userId, command.date, command.platform, command.seconds).getOrThrow()
            is UsageWriteCommand.Pending -> pending.upsertPendingRecognition(command.record)
            is UsageWriteCommand.SettlePending -> pending.removePendingRecognition(command.userId, command.sessionId)
            is UsageWriteCommand.Category -> statistics.resolveShortformCategory(command.userId, command.date, command.sessionId, command.classification).getOrThrow()
            is UsageWriteCommand.Ledger -> ledger.saveInterventionLedger(command.userId, command.date, command.ledger)
            is UsageWriteCommand.Heartbeat -> habits.heartbeat(command.userId, command.date, command.seconds, command.before21, command.occurredAtMs)
        }
    }
}
