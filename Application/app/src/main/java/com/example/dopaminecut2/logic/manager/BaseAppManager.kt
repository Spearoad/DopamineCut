package com.example.dopaminecut2.logic.manager

import android.view.accessibility.AccessibilityNodeInfo

abstract class BaseAppManager : AppManagerInterface {

    protected fun findNodeByText(rootNode: AccessibilityNodeInfo?, keyword: String): Boolean {
        if (rootNode == null) return false

        val text = rootNode.text?.toString().orEmpty()
        val description = rootNode.contentDescription?.toString().orEmpty()

        if (text.contains(keyword, ignoreCase = true) ||
            description.contains(keyword, ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until rootNode.childCount) {
            if (findNodeByText(rootNode.getChild(i), keyword)) return true
        }
        return false
    }

    protected fun findNodeByAnyText(
        rootNode: AccessibilityNodeInfo?,
        keywords: List<String>
    ): Boolean {
        return keywords.any { keyword -> findNodeByText(rootNode, keyword) }
    }

    protected fun findLongestText(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        val currentCandidates = listOf(
            rootNode.text?.toString(),
            rootNode.contentDescription?.toString()
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }

        var longest: String? = currentCandidates.maxByOrNull { it.length }

        for (i in 0 until rootNode.childCount) {
            val childText = findLongestText(rootNode.getChild(i))
            if (!childText.isNullOrBlank() &&
                (longest == null || childText.length > longest.length)
            ) {
                longest = childText
            }
        }

        return longest
    }
}
