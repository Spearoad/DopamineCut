package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo

class InstagramManager : BaseAppManager() {
    /** 해당 매니저가 담당하는 유튜브 앱의 패키지명 */
    override val packageName: String = "" // 인스타그램 패키지명
    /** UI 및 로그 출력에 사용할 플랫폼 이름 */
    override val platformName: String = "Instagram"

    /** 앱 실행 중일 때 현재 화면이 숏폼 영상인지 확인하는 함수
     * 즉, 숏폼 영상인지 확인할 수 있는 내용을 UI 로그를 통해서 찾아내고, 이를 감지하면 True로 반환하게끔
     */
    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        TODO("Not yet implemented")
    }

    /** 동일한 숏폼 영상을 시청해도 숏폼 시청으로 카운트되지 않도록 하기 위해
     * 현재 시청중인 숏폼 영상의 고유한 값을 찾아내서 이를 식별자로 사용하는 함수
     * YoutubeManager.kt 에서는 채널명 + 영상 텍스트로 구성하였음.
     */
    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        TODO("Not yet implemented")
    }

    /** 현재 시청 중인 숏폼 영상이 광고일 경우 true를 반환하는 함수
     * UI 로그를 통해 현재 시청 중인 내용이 광고인지 확인할 수 있는 내용을 찾아내고, 이를 감지하면 True로 반환하게끔
     */
    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        TODO("Not yet implemented")
    }

}