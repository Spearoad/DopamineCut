package com.example.dopaminecut2.auth

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dopaminecut2.R
import com.example.dopaminecut2.logic.UserRepository
import com.example.dopaminecut2.model.User
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth

class SignUpActivity : AppCompatActivity() {

    // Firebase Auth 인스턴스
    private val auth = FirebaseAuth.getInstance()
    // UserRepository 인스턴스 생성
    private val userRepository = UserRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)

        // XML 뷰 연결
        val emailEt = findViewById<EditText>(R.id.et_email)
        val passwordEt = findViewById<EditText>(R.id.et_password)
        val nicknameEt = findViewById<EditText>(R.id.et_nickname)
        val signupBtn = findViewById<Button>(R.id.btn_signup)

        signupBtn.setOnClickListener {
            val email = emailEt.text.toString().trim()
            val password = passwordEt.text.toString().trim()
            val nickname = nicknameEt.text.toString().trim()

            // 1. 유효성 검사
            if (email.isEmpty() || password.isEmpty() || nickname.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Firebase Auth 회원가입 실행
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid

                        if (uid != null) {
                            // 3. User 데이터 클래스 객체 생성
                            val newUser = User(
                                uid = uid,
                                email = email,
                                nickname = nickname,
                                createdAt = Timestamp.now(), // 현재 시간 저장
                                isBanned = false // 기본값 설정
                            )

                            // 4. UserRepository를 통해 Firestore에 저장
                            userRepository.createNewUser(newUser) { isSuccess ->
                                if (isSuccess) {
                                    Toast.makeText(this, "회원가입 성공!", Toast.LENGTH_SHORT).show()
                                    finish() // 가입 완료 후 화면 닫기
                                } else {
                                    Toast.makeText(this, "DB 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        // 가입 실패 (이메일 중복, 비밀번호 취약 등)
                        Toast.makeText(this, "가입 실패: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }
}