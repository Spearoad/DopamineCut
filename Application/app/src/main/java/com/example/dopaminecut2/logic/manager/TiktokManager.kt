package com.example.dopaminecut2.logic.manager

import android.view.accessibility.AccessibilityNodeInfo

class TiktokManager : BaseAppManager() {

    override val packageName = "com.zhiliaoapp.musically"
    override val platformName = "TikTok"

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        return findNodeByAnyText(
            rootNode,
            listOf("팔로잉", "추천", "Following", "For You")
        ) || findLongestText(rootNode) != null
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        return findLongestText(rootNode)
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        return findNodeByAnyText(
            rootNode,
            listOf("Sponsored", "광고", "Ad", "스폰서")
        )
    }
}
