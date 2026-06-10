package com.example.dopaminecut2.logic.manager

import android.view.accessibility.AccessibilityNodeInfo

class InstagramManager : BaseAppManager() {

    override val packageName = "com.instagram.android"
    override val platformName = "Instagram"

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        return findNodeByAnyText(
            rootNode,
            listOf("Reels", "릴스")
        )
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        return findLongestText(rootNode)
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        return findNodeByAnyText(
            rootNode,
            listOf("Sponsored", "광고", "협찬")
        )
    }
}
