package com.example.dopaminecut2.logic

interface AppManagerInterface {
    // 1. 해당 앱의 고유 패키지 이름 (예: com.google.android.youtube)
    val packageName: String
    /**
     * 현재 화면에 해당 앱 혹은 특정 숏폼 UI가 실행 중인지 판별합니다.
     * @param currentPackage 현재 화면의 패키지명
     * @param className 현재 화면의 클래스명 (접근성 서비스에서 받아옴)
     */
    fun isAppRunning(currentPackage: String, className: CharSequence?): Boolean

    /**
     * 사용자가 설정한 제한(시간 또는 횟수)을 초과했는지 확인합니다.
     * @param currentUsage 현재까지의 사용 데이터
     * @param userLimit 사용자가 설정한 제한 값
     */
    fun isLimitExceeded(currentUsage: Long, userLimit: Long): Boolean
}