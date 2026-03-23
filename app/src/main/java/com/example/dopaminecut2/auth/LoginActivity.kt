package com.example.dopaminecut2.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dopaminecut2.MainActivity
import com.example.dopaminecut2.R
import com.example.dopaminecut2.logic.UserRepository // 추가
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    // UserRepository 인스턴스 생성
    private val userRepository = UserRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val emailEt = findViewById<EditText>(R.id.et_login_email)
        val passwordEt = findViewById<EditText>(R.id.et_login_password)
        val loginBtn = findViewById<Button>(R.id.btn_login)
        val signupTv = findViewById<TextView>(R.id.tv_go_to_signup)

        // 1. 로그인 버튼 클릭 시
        loginBtn.setOnClickListener {
            val email = emailEt.text.toString().trim()
            val password = passwordEt.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase Auth 로그인 실행
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid

                        if (uid != null) {
                            // 로그인 성공 후 UserRepository를 통해 유저 정보 가져오기
                            userRepository.getUserInfo(uid) { user ->
                                if (user != null) {
                                    // 닉네임을 포함한 환영 메시지 출력
                                    Toast.makeText(this, "${user.nickname}님, 환영합니다!", Toast.LENGTH_SHORT).show()

                                    // 메인 화면으로 이동
                                    val intent = Intent(this, MainActivity::class.java)
                                    startActivity(intent)
                                    finish()
                                } else {
                                    // 계정은 있으나 DB에 정보가 없는 특수한 경우
                                    Toast.makeText(this, "유저 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "로그인 실패: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        // 2. 회원가입 텍스트 클릭 시 가입 화면으로 이동
        signupTv.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }
    }
}