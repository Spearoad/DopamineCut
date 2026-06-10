package com.example.dopaminecut2.auth

import android.content.Intent
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.databinding.ActivityLoginBinding
import com.example.dopaminecut2.main.BaseActivity
import com.example.dopaminecut2.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity<ActivityLoginBinding>(ActivityLoginBinding::inflate) {

    private val viewModel: AuthViewModel by viewModels()

    override fun initView() {
        // 화면 켜질 때 초기 세팅 (현재는 비워둠)

        // 이미 로그인된 계정이 있으면 바로 MainActivity로 이동
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun setupListeners() {
        // 1. 로그인 버튼 클릭
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val pw = binding.etPassword.text.toString().trim()
            viewModel.login(email, pw)
        }

        // 2. 회원가입 화면으로 이동
        binding.tvGoToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    override fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 1. 로딩 상태(프로그레스 바) 감시
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                        binding.btnLogin.isEnabled = !isLoading
                    }
                }

                // 2. 로그인 성공/실패 이벤트 감시
                launch {
                    viewModel.uiEvent.collect { eventMessage ->
                        if (eventMessage == "LOGIN_SUCCESS") {
                            showToast("로그인 성공!")

                            // [TODO] MainActivity 연결
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        } else {
                            showToast(eventMessage)
                        }
                    }
                }
            }
        }
    }
}