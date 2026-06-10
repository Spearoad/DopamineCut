package com.example.dopaminecut2.logic.manager

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class YoutubeManager : BaseAppManager() {

    override val packageName: String = "com.google.android.youtube"
    override val platformName: String = "YouTube"

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        val shortformKeywords = listOf(
            "Shorts",
            "쇼츠",
            "싫어요",
            "공유",
            "리믹스",
            "Dislike",
            "Share",
            "Remix"
        )

        val matchedCount = shortformKeywords.count { keyword ->
            findNodeByText(rootNode, keyword)
        }

        return matchedCount >= 2
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        return findNodeByAnyText(
            rootNode,
            listOf("Sponsored", "스폰서", "광고", "Ad")
        )
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        val identifierText = findIdentifierText(rootNode)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (identifierText != null) {
            Log.d(TAG, "[YouTube] 영상 식별자 감지: $identifierText")
        }

        return identifierText
    }

    private fun findIdentifierText(rootNode: AccessibilityNodeInfo?): String? {
        return findLongestText(rootNode)
    }

    fun getTrackingId(rootNode: AccessibilityNodeInfo?): String? {
        return getVideoIdentifier(rootNode)
    }

    companion object {
        private const val TAG = "YoutubeManager"
    }
}
