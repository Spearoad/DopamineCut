package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class YoutubeManager : BaseAppManager() {
    /** 해당 매니저가 담당하는 유튜브 앱의 패키지명 */
    override val packageName: String = "com.google.android.youtube"
    /** UI 및 로그 출력에 사용할 플랫폼 이름 */
    override val platformName: String = "YouTube"

    /**
     * 5초 타이머(ViewTracker) 유지용 임시 식별자를 추출함
     * - 영상 로딩 중이거나 시간이 숨겨진 상태에서도 타이머가 끊기지 않도록,
     * 화면에 가장 먼저 뜨고 변하지 않는 고정값인 '@채널명'만 우선적으로 반환함
     */
    fun getTrackingId(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        return findChannelHandle(rootNode)
    }

    /**
     * 현재 화면이 유튜브 숏폼(쇼츠) 재생 화면인지 확실하게 판별함
     * - 일반 홈 화면이나 구독 탭에서 오작동하는 것을 막기 위해,
     * 오직 '숏폼 전용 컨테이너 레이아웃'이 화면에 렌더링되었을 때만 true를 반환함
     */
    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        val shortsCoreIds = listOf(
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container"
        )

        return shortsCoreIds.any { rootNode.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
    }

    /**
     * 중복 시청 검사를 위한 최종 영상 식별자(채널명 + 순수 영상 제목)를 생성함
     * - 타이머가 5초를 달성한 시점에 호출되어, 정확히 어떤 영상을 봤는지 기록하는 용도
     */
    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        val channelHandle = findChannelHandle(rootNode) ?: "UnknownChannel"
        val title = findVideoTitle(rootNode)

        // 메인 화면이거나 데이터를 완전히 불러오지 못해 둘 다 없는 경우 식별 불가 처리
        if (channelHandle == "UnknownChannel" && title == null) return null

        val finalId = "${channelHandle}_${title ?: "NoTitle"}"
        Log.d("DopamineCut", "[Youtube] 영상 식별자 생성: $finalId")

        return finalId
    }

    /** 노드를 재귀적으로 탐색하여 Description이 정확히 "Ad"인 요소를 찾음 */
    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return checkIsAdRecursive(rootNode)
    }

    private fun checkIsAdRecursive(node: AccessibilityNodeInfo): Boolean {
        if (node.contentDescription?.toString() == "Ad") {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (checkIsAdRecursive(child)) return true
        }
        return false
    }

    /** 화면 노드를 순회하며 '@' 기호로 시작하는 유튜브 채널명을 찾아 반환함 */
    private fun findChannelHandle(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (text.startsWith("@")) return text
        if (desc.startsWith("@")) return desc

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findChannelHandle(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * UI 노드 트리의 렌더링 순서(위에서 아래로)를 활용한 영상 제목 추출기
     * - 채널명 바로 아래에 위치하는 유효 텍스트를 찾아 무조건 제목으로 간주함
     * 일부 제목을 다른 텍스트로 인식하지만 어느정도 작동은 해서 냅둠
     */
    private fun findVideoTitle(rootNode: AccessibilityNodeInfo): String? {
        val orderedTexts = mutableListOf<String>()
        // 1. 실제 화면에 노출되는 ViewGroup 텍스트들만 노드 트리 순서대로 수집
        collectViewGroupTexts(rootNode, orderedTexts)

        // 2. 수집된 텍스트 중 채널명(@)이 위치한 인덱스 파악
        val channelIndex = orderedTexts.indexOfFirst { it.startsWith("@") }
        if (channelIndex == -1) return null // 채널명이 없으면 탐색 불가

        // 3. 채널명 바로 밑에 위치할 수 있는 구독 관련 시스템 버튼 텍스트는 필터링 대상
        val ignoreWords = setOf("Subscribe", "Subscribed", "구독", "구독중") // UI 로그에서는 채널명 바로 밑에 구독 버튼이 있음

        // 4. 채널명 인덱스의 바로 '다음' 텍스트부터 순차 탐색
        for (i in (channelIndex + 1) until orderedTexts.size) {
            val text = orderedTexts[i]

            if (ignoreWords.contains(text)) continue // 구독 버튼은 건너뜀

            // 구독 버튼이 아닌 첫 번째 텍스트가 발견되면, 영상 제목으로 인식함
            if (text.isNotEmpty()) {
                return text
            }
        }

        return null
    }

    /**
     * 안드로이드 AccessibilityNodeInfo 중에서 '값이 있는 텍스트'만 수집함
     */
    private fun collectViewGroupTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (node.className?.toString() == "android.view.ViewGroup") {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (!text.isNullOrEmpty()) {
                list.add(text)
            }
        }

        // 안드로이드 뷰 트리가 구성된 순서 그대로 재귀 탐색 (화면의 위->아래 순서로 진행함)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectViewGroupTexts(child, list)
        }
    }
}