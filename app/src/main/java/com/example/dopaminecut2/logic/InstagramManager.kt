package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo

class InstagramManager : BaseAppManager() {
    override val packageName: String = "com.instagram.android"
    override val platformName: String = "Instagram"

    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        if (isAdContent(rootNode)) return false
        val reelsIds = listOf(
            "com.instagram.android:id/clips_ufi_component",
            "com.instagram.android:id/reels_viewer_pager",
            "com.instagram.android:id/swipeable_tab_view_pager"
        )
        return reelsIds.any { rootNode.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
    }

    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        // 1. 제작자 ID (기존 검증 로직 유지)
        val accountName = findAccountNameFromDesc(rootNode) ?: "UnknownUser"

        // 2. 제목 추출 - 실제 영상 및 텍스트 본문이 담기는 컨테이너 타겟팅
        val mainContent = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/reels_viewer_pager").firstOrNull()
            ?: rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/swipeable_tab_view_pager").firstOrNull()
            ?: rootNode

        // 3. 텍스트 추출 로직 (블랙리스트 없이 본문 특징으로만 탐색)
        val caption = findReelsCaption(mainContent) ?: "NoCaption"

        val cleanCaption = caption.replace("\n", " ").trim().let {
            if (it.length > 20) it.substring(0, 20) else it
        }

        return "$accountName $cleanCaption"
    }

    /**
     * 릴스 본문(제목)의 특징:
     * - 주로 android.view.ViewGroup의 Description에 긴 문장으로 존재함
     * - 숫자만 있거나(@213...), '회', '개' 등 단위로 끝나는 짧은 텍스트(저장 횟수)가 아님
     */
    private fun findReelsCaption(node: AccessibilityNodeInfo): String? {
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val target = if (desc.isNotEmpty()) desc else text

        // [핵심 로직] 본문은 보통 10자 이상이며, 숫자만 있는 ID나 단위성 텍스트가 아님
        if (target.length > 8 &&
            !target.contains("회") &&
            !target.contains("개") &&
            !target.startsWith("@") &&
            !target.contains("님이 만든 릴스")) {
            return target
        }

        // 역순 탐색 (화면 하단에 본문이 위치하므로 자식 노드를 뒤에서부터 확인)
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            val result = findReelsCaption(child)
            if (result != null) return result
        }
        return null
    }

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val adIds = listOf(
            "com.instagram.android:id/reels_ad_label_text",
            "com.instagram.android:id/ads_media_content",
            "com.instagram.android:id/reels_single_image_ad_tagline"
        )
        if (adIds.any { rootNode.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }) return true

        val text = rootNode.text?.toString() ?: ""
        val desc = rootNode.contentDescription?.toString() ?: ""
        if (text == "광고" || desc == "광고" || text.equals("Sponsored", true)) return true

        return hasKeywordInTree(rootNode, "광고")
    }

    private fun hasKeywordInTree(node: AccessibilityNodeInfo, keyword: String): Boolean {
        if (node.text?.toString() == keyword || node.contentDescription?.toString() == keyword) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasKeywordInTree(child, keyword)) return true
        }
        return false
    }

    private fun findAccountNameFromDesc(node: AccessibilityNodeInfo): String? {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains("님이 만든 릴스입니다")) return desc.split("님이")[0].trim()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAccountNameFromDesc(child)
            if (found != null) return found
        }
        return null
    }
}
