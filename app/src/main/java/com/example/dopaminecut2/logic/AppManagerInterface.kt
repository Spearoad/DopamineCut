package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo

interface AppManagerInterface {
    /** 해당 앱의 고유 패키지 이름 (ex: com.google.android.youtube) */
    val packageName: String

    /** 화면 UI(통계/리포트)에 보여줄 앱 이름 (ex: "YouTube", "Instagram") */
    val platformName: String

    /** 현재 화면에 해당 앱이 실행 중인지 판별함. */
    fun isAppRunning(currentPackage: String, className: CharSequence?): Boolean

    /** 현재 화면이 숏폼(쇼츠/릴스/틱톡)인지 판별 */
    fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean

    /** 영상 고유 식별자 추출 (중복 시청 방지용) */
    fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String?

    /** 현재 화면의 광고 식별 */
    fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean

}