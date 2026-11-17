package com.example.dopaminecut2.ui.settings

import com.example.dopaminecut2.ui.common.MeasurementPermissionState
import com.example.dopaminecut2.data.local.NotificationOption
import com.example.dopaminecut2.data.local.NotificationSettings

data class AccountSummaryUi(val nickname: String, val email: String)

data class SettingsUiState(
    val analysis: com.example.dopaminecut2.data.local.AnalysisSettings = com.example.dopaminecut2.data.local.AnalysisSettings(),
    val analysisLoaded: Boolean = false,
    val isChangingData: Boolean = false,
    val account: AccountSummaryUi? = null,
    val permissions: MeasurementPermissionState = MeasurementPermissionState(false, false),
    val isLoading: Boolean = true,
    val isLoggingOut: Boolean = false,
    val isSyncingUsage: Boolean = false,
    val notifications: NotificationSettings = NotificationSettings(),
    val notificationsLoaded: Boolean = false,
    val isSavingNotifications: Boolean = false,
    val isSavingMeasurement: Boolean = false,
    val message: String? = null
)

sealed interface SettingsAction {
    data class SetAnalysis(val measurement: Boolean, val content: Boolean) : SettingsAction
    data class DeleteRecords(val all: Boolean) : SettingsAction
    data object Refresh : SettingsAction
    data object Logout : SettingsAction
    data object SyncUsage : SettingsAction
    data class FinishMeasurementSetup(val deferred: Boolean) : SettingsAction
    data object ClearMessage : SettingsAction
    data class SetNotification(val option: NotificationOption, val enabled: Boolean) : SettingsAction
}
