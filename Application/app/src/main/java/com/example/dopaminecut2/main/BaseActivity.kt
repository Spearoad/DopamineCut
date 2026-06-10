package com.example.dopaminecut2.main

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

/**
 * 프로젝트 내 모든 Activity의 공통 부모 클래스
 * @param inflate ViewBinding을 인플레이트하기 위한 함수
 */
abstract class BaseActivity<T : ViewBinding>(
    private val inflate: (LayoutInflater) -> T
) : AppCompatActivity() {

    // 자식 클래스(LoginActivity 등)에서 'binding.etEmail' 처럼 접근할 수 있는 뷰바인딩 객체
    protected lateinit var binding: T
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding 자동 초기화
        binding = inflate(layoutInflater)
        setContentView(binding.root)

        // 자식 클래스에서 강제로 구현해야 할 초기 세팅 함수들 자동 호출
        initView()
        setupListeners()
        observeViewModel()
    }

    /** 화면 기본 세팅을 수행하는 함수 (자식 클래스에서 구현하기) */
    abstract fun initView()

    /** 버튼 클릭 등 이벤트를 세팅 (선택 사항) */
    open fun setupListeners() {}

    /** ViewModel의 데이터를 감시(Observe)하는 함수 (선택 사항) */
    open fun observeViewModel() {}

    /** * Toast 메시지 띄우기 함수
     * 자식 클래스에서 showToast("메시지") 형태로 사용
     */
    protected fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}