package com.example.dopaminecut2.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.dopaminecut2.R
import com.example.dopaminecut2.databinding.FragmentCommunityBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnOpenCommunityWeb.setOnClickListener { openCommunityWeb() }
    }

    private fun openCommunityWeb() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val uid = user.uid
                var nickname = user.email ?: "사용자"
                var score = 100

                val userSnap = firestore.collection("users").document(uid).get().await()
                nickname = userSnap.getString("nickname") ?: nickname

                val cal = Calendar.getInstance()
                val dateKey = buildString {
                    append(uid)
                    append('_')
                    append(cal.get(Calendar.YEAR))
                    append(String.format("%02d", cal.get(Calendar.MONTH) + 1))
                    append(String.format("%02d", cal.get(Calendar.DAY_OF_MONTH)))
                }
                val statSnap = firestore.collection("daily_statistics").document(dateKey).get().await()
                if (statSnap.exists()) {
                    val penalty = statSnap.getLong("daily_score") ?: 0L
                    score = (100 - penalty).toInt().coerceIn(0, 100)
                }

                val baseUrl = getString(R.string.community_web_base_url).trimEnd('/')
                val uri = Uri.parse(baseUrl).buildUpon()
                    .appendQueryParameter("uid", uid)
                    .appendQueryParameter("score", score.toString())
                    .appendQueryParameter("nickname", nickname)
                    .build()

                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "커뮤니티 연결 실패: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
