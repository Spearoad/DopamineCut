package com.example.dopaminecut2.personal

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.dopaminecut2.R
import com.example.dopaminecut2.logic.ShortformSettingActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// Fragment(R.layout.fragment_dopamine_setting)를 통해 XML과 바로 연결됩니다.
class DopamineSetting : Fragment(R.layout.fragment_dopamine_setting) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 사용자 닉네임 로드
        val uid = auth.currentUser?.uid

        uid?.let {
            db.collection("Users").document(it).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                    }
                }
        }

        // 숏폼 로직팀 영역: 설정 페이지 이동 버튼
        val shortformBtn = view.findViewById<Button>(R.id.btn_go_to_shortform_setting)
        shortformBtn.setOnClickListener {
            val intent = Intent(requireContext(), ShortformSettingActivity::class.java)
            startActivity(intent)
        }
    }
}