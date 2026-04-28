package com.example.dopaminecut2.logic

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class KakaotalkManager : BaseAppManager()  {
    /** 해당 매니저가 담당하는 유튜브 앱의 패키지명 */

    override val packageName: String = "com.kakao.talk"
    /** UI 및 로그 출력에 사용할 플랫폼 이름 */
    override val platformName: String = "Kakaotalk"

    /** 앱 실행 중일 때 현재 화면이 숏폼 영상인지 확인하는 함수
     * 즉, 숏폼 영상인지 확인할 수 있는 내용을 UI 로그를 통해서 찾아내고, 이를 감지하면 True로 반환하게끔
     */
    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        val playerViewNodes = rootNode.findAccessibilityNodeInfosByViewId("com.kakao.talk:id/player_view")
        val shortFormPlayerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.kakao.talk.shortform_presentation:id/shortFormPlayer")

        val hasPlayer = playerViewNodes.isNotEmpty() || shortFormPlayerNodes.isNotEmpty()
        val isAd = isAdContent(rootNode)

        Log.d("Kakaotalk_Log", "숏폼 조건 탐지 -> player: $hasPlayer / 광고: $isAd")

        return hasPlayer && !isAd
    }

    /** 동일한 숏폼 영상을 시청해도 숏폼 시청으로 카운트되지 않도록 하기 위해
     * 현재 시청중인 숏폼 영상의 고유한 값을 찾아내서 이를 식별자로 사용하는 함수
     * YoutubeManager.kt 에서는 채널명 + 영상 텍스트로 구성하였음.
     */
    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        // 작성자 이름 추출
        val nameNodes = rootNode.findAccessibilityNodeInfosByViewId("com.kakao.talk:id/infoName")
        val uploader = nameNodes.firstOrNull { isOnScreen(it) }?.text?.toString()
            ?: nameNodes.firstOrNull { it.isVisibleToUser }?.text?.toString()
            ?: nameNodes.firstOrNull()?.text?.toString()
            ?: "Unknown"

        // 숏폼 본문 내용 추출
        val descNodes = rootNode.findAccessibilityNodeInfosByViewId("com.kakao.talk:id/infoDesc")
        var description = descNodes.firstOrNull { isOnScreen(it) }?.text?.toString()
            ?: descNodes.firstOrNull { it.isVisibleToUser }?.text?.toString()
            ?: descNodes.firstOrNull()?.text?.toString()
            ?: "NoDesc"

        // 카카오톡 본문에 줄바꿈이 있는 경우 한 줄로 다듬기
        description = description.replace("\n", " ").trim()

        // 식별자가 길어지는 것을 방지 (30자 자르기)
        val safeDescription = if (description.length > 30) description.substring(0, 30) else description

        val finalId = "${uploader}_${safeDescription}"

        // 확인용 로그
        Log.d("Kakaotalk_Log", "생성된 식별자: $finalId")

        return finalId
    }

    /** 현재 시청 중인 숏폼 영상이 광고일 경우 true를 반환하는 함수
     * UI 로그를 통해 현재 시청 중인 내용이 광고인지 확인할 수 있는 내용을 찾아내고, 이를 감지하면 True로 반환하게끔
     */
    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        // 카톡 숏폼 전용 광고 레이아웃 ID 감지
        val feedAdLayouts = rootNode.findAccessibilityNodeInfosByViewId("com.kakao.talk.shortform_presentation:id/feedAdLayout")
        if (feedAdLayouts.any { isOnScreen(it) }) {
            Log.d("Kakaotalk_Log", "광고 인식됨. (화면 내 feedAdLayout 발견)")
            return true
        }

        // 텍스트 감지 ("광고" 글자가 숏폼 영역 안에 있을 때만 인정)
        val adTextNodes = rootNode.findAccessibilityNodeInfosByText("광고")
        for (node in adTextNodes) {
            if (node.text?.toString() == "광고") {
                if (isInShortformHierarchy(node) && isOnScreen(node)) {
                    Log.d("Kakaotalk_Log", "광고 인식됨. (화면 내 '광고' 텍스트 발견)")
                    return true
                }
            }
        }

        return false
    }

    /** 핵심 기술: 해당 노드가 숏폼 영역(shortform_presentation) 안에 있는지 검사하는 함수 */
    private fun isInShortformHierarchy(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 15) {
            val id = current.viewIdResourceName
            if (id != null && id.contains("shortform_presentation")) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * 영상의 제목을 미리 불러오는 문제 발생 → 해결을 위해 추가한 함수
     * 현재 보이는 화면 속 글자만을 불러온다.
     * 즉, 화면 이전/다음 영상의 데이터 걸러내기.
     * */

    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 현재 스마트폰 화면의 가로, 세로 픽셀 크기 가져오기
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenRect = Rect(0, 0, screenWidth, screenHeight)

        // 노드의 좌표 영역이 폰 화면 영역과 겹치는지 검사
        return !rect.isEmpty && Rect.intersects(screenRect, rect)
    }
}