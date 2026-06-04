package com.example.dopaminecut2.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> get() = _isLoading

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> get() = _uiEvent

    private fun validateInput(email: String, pw: String): Boolean {
        if (email.isBlank() || pw.isBlank()) {
            emitEvent("이메일과 비밀번호를 모두 입력해주세요.")
            return false
        }
        if (!email.contains("@")) {
            emitEvent("올바른 이메일 형식을 입력해주세요.")
            return false
        }
        if (pw.length < 6) {
            emitEvent("비밀번호는 6자리 이상이어야 합니다.")
            return false
        }
        return true
    }

    fun login(email: String, pw: String) {
        if (!validateInput(email, pw)) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                auth.signInWithEmailAndPassword(email, pw).await()
                emitEvent("LOGIN_SUCCESS")
            } catch (e: Exception) {
                emitEvent(mapAuthError(e, "로그인"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signup(email: String, pw: String, nickname: String) {
        if (!validateInput(email, pw)) return
        if (nickname.isBlank()) {
            emitEvent("닉네임을 입력해주세요.")
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            var createdUid: String? = null
            try {
                val authResult = auth.createUserWithEmailAndPassword(email, pw).await()
                createdUid = authResult.user?.uid ?: throw IllegalStateException("UID 생성 실패")

                val userData = hashMapOf(
                    "email" to email,
                    "nickname" to nickname,
                    "created_at" to com.google.firebase.Timestamp.now(),
                    "restrictions" to emptyList<String>(),
                    "inventory" to hashMapOf(
                        "poke" to 0L,
                        "megaphone" to 0L
                    )
                )

                firestore.collection("users").document(createdUid).set(userData).await()
                emitEvent("SIGNUP_SUCCESS")
            } catch (e: Exception) {
                rollbackAuthUser(createdUid)
                emitEvent(mapSignupError(e))
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun rollbackAuthUser(uid: String?) {
        if (uid == null) return
        try {
            val user = auth.currentUser
            if (user != null && user.uid == uid) {
                user.delete().await()
            }
        } catch (_: Exception) {
            // Firestore만 실패한 경우 재가입을 위해 Auth 계정 삭제 시도
        }
    }

    private fun mapSignupError(e: Exception): String {
        if (e is FirebaseFirestoreException) {
            return when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "회원가입 실패: Firestore 권한이 없습니다. Firebase 콘솔에서 Firestore·보안 규칙을 확인하세요."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "회원가입 실패: Firestore가 아직 생성되지 않았을 수 있습니다. 콘솔에서 Firestore 데이터베이스를 만드세요."
                else ->
                    "회원가입 실패 (DB): ${e.message ?: e.code.name}"
            }
        }
        return mapAuthError(e, "회원가입")
    }

    private fun mapAuthError(e: Exception, action: String): String {
        when (e) {
            is FirebaseAuthUserCollisionException ->
                return "$action 실패: 이미 사용 중인 이메일입니다."
            is FirebaseAuthWeakPasswordException ->
                return "$action 실패: 비밀번호가 너무 약합니다. 6자리 이상으로 설정하세요."
            is FirebaseAuthInvalidCredentialsException ->
                return "$action 실패: 이메일 또는 비밀번호 형식이 올바르지 않습니다."
            is FirebaseAuthException -> {
                if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED") {
                    return "$action 실패: Firebase에서 이메일/비밀번호 로그인을 켜주세요. (Authentication → Sign-in method)"
                }
                return "$action 실패: ${e.message ?: e.errorCode}"
            }
            is FirebaseNetworkException ->
                return "$action 실패: 인터넷 연결을 확인해주세요."
        }
        return "$action 실패: ${e.localizedMessage ?: e.message ?: "알 수 없는 오류"}"
    }

    private fun emitEvent(message: String) {
        viewModelScope.launch {
            _uiEvent.emit(message)
        }
    }
}
