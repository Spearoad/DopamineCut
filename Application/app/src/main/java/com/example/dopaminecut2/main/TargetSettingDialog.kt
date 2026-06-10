package com.example.dopaminecut2.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.dopaminecut2.databinding.DialogTargetSettingBinding
import kotlinx.coroutines.launch

// 다이얼로그로 띄우기 위해 DialogFragment 상속.
class TargetSettingDialog : DialogFragment() {

    private var _binding: DialogTargetSettingBinding? = null
    private val binding get() = _binding!!

    // HomeFragment와 똑같은 뷰모델 공유.
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogTargetSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 팝업창 가로 길이를 화면에 꽉 차게 맞추기.
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        setupListeners()
        observeSaveResult()
    }

    private fun setupListeners() {

        // 체크박스 5개 제한 Logic
        val checkBoxes = listOf(
            binding.cbTag1, binding.cbTag2, binding.cbTag3,
            binding.cbTag4, binding.cbTag5, binding.cbTag6,
            binding.cbTag7, binding.cbTag8, binding.cbTag9
        )

        checkBoxes.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { buttonView, isChecked ->
                // 현재 체크된 개수 세기
                val checkedCount = checkBoxes.count { it.isChecked }
                if (checkedCount > 5) {
                    // 5개를 넘으면 마지막 체크를 강제 해제
                    buttonView.isChecked = false
                    Toast.makeText(requireContext(), "태그는 5개까지 선택할 수 있습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 취소 버튼 누르면 팝업 닫기
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        // 저장 버튼 클릭 시 뷰모델로 데이터 전달
        binding.btnSave.setOnClickListener {
            val timeLimitStr = binding.etTimeLimit.text.toString()
            val countLimitStr = binding.etCountLimit.text.toString()

            if (timeLimitStr.isEmpty() || countLimitStr.isEmpty()) {
                Toast.makeText(requireContext(), "값을 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 체크된 태그 필터링, 글자만 리스트로 뽑기
            val selectedTags = checkBoxes.filter { it.isChecked }.map { it.text.toString() }

            // 뷰모델로 데이터 전달
            viewModel.saveNewTarget(timeLimitStr.toInt(), countLimitStr.toInt(), selectedTags)
        }
    }

    // 뷰모델의 저장 결과 감시하여 팝업 닫거나 에러 메시지 띄우기
    private fun observeSaveResult() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.targetSaveEvent.collect { message ->
                    if (message == "TARGET_SAVE_SUCCESS") {
                        Toast.makeText(requireContext(), "목표가 정상적으로 저장되었습니다.", Toast.LENGTH_SHORT).show()
                        dismiss() // 성공하면 팝업 닫기
                    } else {
                        // 40% 초과 등 에러면 팝업 안 닫고 토스트만 띄우기
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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