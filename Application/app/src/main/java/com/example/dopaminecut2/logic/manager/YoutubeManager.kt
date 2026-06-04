package com.example.dopaminecut2.logic.manager

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class YoutubeManager : BaseAppManager() {

    override val packageName: String = "com.google.android.youtube"
    override val platformName: String = "YouTube"

    private val shortsKeywords = listOf(
        "싫어요", "공유", "리믹스",
        "Dislike", "Share", "Remix"
    )

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val matchCount = shortsKeywords.count { findNodeByText(rootNode, it) }
        return matchCount >= 2
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return findNodeByText(rootNode, "Sponsored") ||
            findNodeByText(rootNode, "스폰서") ||
            findNodeByText(rootNode, "광고")
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        val identifierText = findLongestText(rootNode) ?: return null
        Log.d("DopamineCut", "[Youtube] 영상 식별자 감지: $identifierText")
        return identifierText
    }
}
