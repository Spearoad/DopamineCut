package com.example.dopaminecut2.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.auth.LoginActivity
import com.example.dopaminecut2.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // 뷰모델 연결
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 로그아웃 버튼 클릭 이벤트
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
        }

        // 로그아웃 결과 감시
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    if (event == "LOGOUT_SUCCESS") {
                        Toast.makeText(requireContext(), "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()

                        // 로그아웃 성공 시 LoginActivity로 이동
                        val intent = Intent(requireContext(), LoginActivity::class.java)
                        // 뒤로가기 눌러서 다시 메인화면으로 못 오게, 기존 화면 기록 지우기
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)

                        requireActivity().finish() // 현재 메인 화면 완전 종료
                    } else {
                        Toast.makeText(requireContext(), event, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}