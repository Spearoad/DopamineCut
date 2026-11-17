package com.example.dopaminecut2.logic.manager

import com.example.dopaminecut2.logic.shortform.ShortformContentKind

/** Platform policy input. No Android service, model or coroutine ownership leaks into a manager. */
data class ContentTrackingContext(
    val contentKey: String,
    val activeKey: String?,
    val kind: ShortformContentKind,
    val inferenceRunning: Boolean,
    val knownNonReel: Boolean = false
)

/** Managers choose the next step; the service executes the shared side effects. */
sealed interface ContentTrackingCommand {
    data class BeginClassification(val provisional: Boolean = false) : ContentTrackingCommand
    data object RetryClassification : ContentTrackingCommand
    data class Track(val kind: ShortformContentKind, val cancelInference: Boolean = false) : ContentTrackingCommand
    data object Wait : ContentTrackingCommand
}
