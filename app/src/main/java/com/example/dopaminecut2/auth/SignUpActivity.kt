package com.example.dopaminecut2.auth

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.dopaminecut2.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SignUpActivity : AppCompatActivity() {

    // Firebase 인스턴스 초기화
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 레이아웃 연결
        setContentView(R.layout.activity_sign_up)

        // XML의 ID와 연결
        val emailEt = findViewById<EditText>(R.id.et_email)
        val passwordEt = findViewById<EditText>(R.id.et_password)
        val nicknameEt = findViewById<EditText>(R.id.et_nickname)
        val signupBtn = findViewById<Button>(R.id.btn_signup)

        signupBtn.setOnClickListener {
            val email = emailEt.text.toString()
            val password = passwordEt.text.toString()
            val nickname = nicknameEt.text.toString()

            // 유효성 검사
            if (email.isEmpty() || password.isEmpty() || nickname.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase 회원가입 실행
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid
                        val user = hashMapOf(
                            "nickname" to nickname,
                            "goal_count" to 0,
                            "is_banned" to false
                        )

                        uid?.let {
                            db.collection("Users").document(it).set(user)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "회원가입 성공!", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                        }
                    } else {
                        Toast.makeText(this, "가입 실패: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }
}