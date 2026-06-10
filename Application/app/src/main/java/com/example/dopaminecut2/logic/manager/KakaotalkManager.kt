package com.example.dopaminecut2.logic.manager

import android.view.accessibility.AccessibilityNodeInfo

class KakaotalkManager : BaseAppManager() {

    override val packageName = "com.kakao.talk"
    override val platformName = "KakaoTalk"

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        return findNodeByAnyText(
            rootNode,
            listOf("재생", "동영상", "쇼츠", "shorts")
        )
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        return findLongestText(rootNode)
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        return findNodeByAnyText(
            rootNode,
            listOf("광고", "Sponsored", "스폰서")
        )
    }
}
