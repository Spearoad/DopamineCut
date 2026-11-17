package com.example.dopaminecut2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dopaminecut2.auth.AuthRepository
import com.example.dopaminecut2.data.local.OnboardingStore
import com.example.dopaminecut2.data.local.NotificationSettingsStore
import com.example.dopaminecut2.data.local.NotificationOption
import com.example.dopaminecut2.data.local.NotificationSettings
import com.example.dopaminecut2.data.repository.UserRepositoryInterface
import com.example.dopaminecut2.ui.common.MeasurementPermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepositoryInterface,
    private val onboardingStore: OnboardingStore,
    private val permissionChecker: MeasurementPermissionChecker,
    private val notificationSettingsStore: NotificationSettingsStore,
    private val habits: com.example.dopaminecut2.data.local.HabitStore? = null,
    private val dataControls: com.example.dopaminecut2.data.repository.DataControls? = null,
    private val syncUsage: suspend (String) -> com.example.dopaminecut2.statistics.UsageSyncResult = { com.example.dopaminecut2.statistics.UsageSyncResult(failed = true) }
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var notificationOwner: String? = null
    private var loadingNotifications = false

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetAnalysis -> updateAnalysis(action)
            is SettingsAction.DeleteRecords -> deleteRecords(action.all)
            SettingsAction.Refresh -> refresh()
            SettingsAction.Logout -> logout()
            SettingsAction.SyncUsage -> synchronizeUsage()
            is SettingsAction.FinishMeasurementSetup -> finishMeasurement(action.deferred)
            SettingsAction.ClearMessage -> _uiState.value = _uiState.value.copy(message = null)
            is SettingsAction.SetNotification -> saveNotification(action.option, action.enabled)
        }
    }

    fun refresh() {
        val userId = authRepository.currentUserId()
        _uiState.value = _uiState.value.copy(
            permissions = permissionChecker.currentState(),
            isLoading = userId != null && _uiState.value.account == null
        )
        if (userId == null) {
            notificationOwner = null
            loadingNotifications = false
            _uiState.value = _uiState.value.copy(
                account = null,
                analysisLoaded = false,
                analysis = com.example.dopaminecut2.data.local.AnalysisSettings(),
                notifications = NotificationSettings(),
                notificationsLoaded = false,
                isSavingNotifications = false
            )
            return
        }
        loadNotifications(userId)
        viewModelScope.launch {
            runCatching { habits?.settings(userId)?.first() }.onSuccess { settings ->
                if (authRepository.currentUserId() == userId && settings != null) _uiState.value = _uiState.value.copy(analysis = settings, analysisLoaded = true)
            }.onFailure { _uiState.value = _uiState.value.copy(message = "분석 설정 조회 실패: ${it.message}") }
            userRepository.getUserInfo(userId)
                .onSuccess { user ->
                    if (authRepository.currentUserId() == userId) {
                        _uiState.value = _uiState.value.copy(
                            account = AccountSummaryUi(
                                user.nickname,
                                authRepository.currentUserEmail().orEmpty()
                            ),
                            permissions = permissionChecker.currentState(),
                            isLoading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = error.localizedMessage ?: "계정 정보를 불러오지 못했습니다."
                    )
                }
        }
    }

    private fun loadNotifications(userId: String) {
        if (notificationOwner == userId && (_uiState.value.notificationsLoaded || loadingNotifications)) return
        notificationOwner = userId
        loadingNotifications = true
        _uiState.value = _uiState.value.copy(
            notifications = NotificationSettings(),
            notificationsLoaded = false,
            isSavingNotifications = false
        )
        viewModelScope.launch {
            try {
                val settings = notificationSettingsStore.observeNotificationSettings(userId).first()
                if (authRepository.currentUserId() == userId && notificationOwner == userId) {
                    _uiState.value = _uiState.value.copy(notifications = settings, notificationsLoaded = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authRepository.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(message = "알림 설정을 불러오지 못했습니다. 다시 확인해 주세요.")
                }
            } finally {
                if (notificationOwner == userId) loadingNotifications = false
            }
        }
    }

    private fun saveNotification(option: NotificationOption, enabled: Boolean) {
        val userId = authRepository.currentUserId() ?: return
        val current = _uiState.value
        if (!current.notificationsLoaded || current.isSavingNotifications || notificationOwner != userId) return
        val settings = current.notifications.withEnabled(option, enabled)
        _uiState.value = current.copy(isSavingNotifications = true)
        viewModelScope.launch {
            try {
                notificationSettingsStore.saveNotificationSettings(userId, settings)
                if (authRepository.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(notifications = settings)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authRepository.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(message = "알림 설정을 저장하지 못했습니다. 다시 시도해 주세요.")
                }
            } finally {
                if (authRepository.currentUserId() == userId) {
                    _uiState.value = _uiState.value.copy(isSavingNotifications = false)
                }
            }
        }
    }

    private fun logout() {
        if (_uiState.value.isLoggingOut) return
        _uiState.value = _uiState.value.copy(isLoggingOut = true, message = null)
        viewModelScope.launch {
            val uid = authRepository.currentUserId()
            if (uid != null) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                        dataControls?.quiesceService?.invoke()
                        syncUsage(uid)
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* Persistent dirty records remain associated with the old uid. */ }
            }
            authRepository.logout()
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoggingOut = false,
                    message = error.localizedMessage ?: "로그아웃하지 못했습니다."
                )
            }
        }
    }
    private fun synchronizeUsage() {
        val uid = authRepository.currentUserId() ?: return
        if (_uiState.value.isSyncingUsage || _uiState.value.isChangingData || _uiState.value.isLoggingOut) return
        _uiState.value = _uiState.value.copy(isSyncingUsage = true)
        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(120_000L) { syncUsage(uid) }
                if (authRepository.currentUserId() == uid) _uiState.value = _uiState.value.copy(message = when {
                    result == null || result.failed -> "동기화하지 못한 기록은 기기에 보관됩니다. 네트워크 또는 서버 기록 충돌을 확인해 주세요."
                    else -> "사용 기록 동기화 완료 · ${result.synced}일 반영"
                })
            } finally { _uiState.value = _uiState.value.copy(isSyncingUsage = false) }
        }
    }
    private fun updateAnalysis(action: SettingsAction.SetAnalysis) {
        val uid = authRepository.currentUserId() ?: return
        if (_uiState.value.isChangingData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChangingData = true)
            runCatching { requireNotNull(habits).setSettings(uid, com.example.dopaminecut2.data.local.AnalysisSettings(action.measurement, action.content)) }
                .onSuccess { if (authRepository.currentUserId() == uid) _uiState.value = _uiState.value.copy(
                    analysis = com.example.dopaminecut2.data.local.AnalysisSettings(action.measurement, action.content),
                    message = if (action.measurement) "기록을 계속합니다. 접근성 연결 상태도 확인해 주세요." else "기록과 개입·비교 알림을 중지했습니다.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "설정 저장 실패: ${it.message}") }
            _uiState.value = _uiState.value.copy(isChangingData = false)
        }
    }
    private fun deleteRecords(all: Boolean) {
        val uid = authRepository.currentUserId() ?: return
        if (_uiState.value.isChangingData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isChangingData = true)
            val result = dataControls?.delete(uid, if (all) null else java.time.LocalDate.now().format(com.example.dopaminecut2.domain.HabitInsightsPolicy.format))
                ?: Result.failure(IllegalStateException("삭제 기능이 연결되지 않았습니다."))
            if (authRepository.currentUserId() == uid) _uiState.value = _uiState.value.copy(isChangingData = false,
                analysis = _uiState.value.analysis.copy(measurement = false),
                message = result.fold({ "${if (all) "전체" else "오늘"} 사용 기록을 삭제했습니다. 복구할 수 없으며 기록은 중지 상태입니다. 계정과 목표는 유지됩니다." },
                    { "삭제를 완료하지 못했습니다: ${it.message}. 기록은 중지 상태로 유지됩니다. 다시 삭제해 주세요." }))
        }
    }

    private fun finishMeasurement(deferred: Boolean) {
        val userId = authRepository.currentUserId() ?: return
        if (_uiState.value.isSavingMeasurement) return
        val permissions = permissionChecker.currentState()
        if (!deferred && (!permissions.accessibilityGranted || !permissions.usageAccessGranted)) {
            _uiState.value = _uiState.value.copy(permissions = permissions, message = "측정 권한을 확인해 주세요.")
            return
        }
        _uiState.value = _uiState.value.copy(isSavingMeasurement = true, message = null)
        viewModelScope.launch {
            try {
                onboardingStore.finishMeasurementSetup(userId, deferred, System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (authRepository.currentUserId() == userId) _uiState.value = _uiState.value.copy(
                    message = "측정 설정 상태를 저장하지 못했습니다. 다시 시도해 주세요."
                )
            } finally {
                if (authRepository.currentUserId() == userId) _uiState.value = _uiState.value.copy(isSavingMeasurement = false)
            }
        }
    }
}
