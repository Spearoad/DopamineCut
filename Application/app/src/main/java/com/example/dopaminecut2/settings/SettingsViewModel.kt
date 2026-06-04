package com.example.dopaminecut2.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> get() = _uiEvent

    fun logout() {
        viewModelScope.launch {
            try {
                auth.signOut()
                _uiEvent.emit("LOGOUT_SUCCESS")
            } catch (e: Exception) {
                _uiEvent.emit("로그아웃 실패: ${e.localizedMessage}")
            }
        }
    }
}
