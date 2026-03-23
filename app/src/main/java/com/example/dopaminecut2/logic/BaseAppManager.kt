package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

abstract class BaseAppManager : AppManagerInterface {

    /** 시청된 영상 식별자를 저장하여 중복 카운팅 방지하는 메모리 캐시 */
    protected val viewed = mutableSetOf<String>()

    /** 현재 화면의 패키지명이 해당 앱의 패키지명과 일치하는지 판별함 */
    override fun isAppRunning(currentPackage: String, className: CharSequence?): Boolean {
        return currentPackage == packageName
    }

    /** UI 트리(화면)에서 특정 클래스(예: ViewGroup)를 가진 노드의 텍스트와 설명을 재귀적으로 모두 추출함 */
    protected fun extractTextsFromNode(
        node: AccessibilityNodeInfo?,
        targetClassName: String,
        resultList: MutableList<String>
    ) {
        if (node == null || !node.isVisibleToUser) return

        // 클래스명이 일치하면 화면에 보이는 텍스트나 설명을 리스트에 수집
        if (node.className?.toString() == targetClassName) {
            val content = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (content.isNotBlank()) {
                resultList.add(content)
            }
        }

        // 자식 노드들을 계속해서 타고 내려가며 탐색
        for (i in 0 until node.childCount) {
            extractTextsFromNode(node.getChild(i), targetClassName, resultList)
        }
    }

    /** 추출된 영상 식별자를 바탕으로 이미 시청한 영상인지 중복 여부를 판별함 (true: 중복, false: 새 영상) */
    fun isDuplicated(videoId: String?): Boolean {
        if (videoId.isNullOrBlank()) return true // 식별자가 없으면 중복(카운트 제외)으로 처리

        if (viewed.contains(videoId)) {
            return true
        }
        viewed.add(videoId)
        return false
    }

    /** 자정 갱신 등 세션 초기화 시, 누적된 시청 기록(캐시)을 모두 비움 */
    fun clearCache() {
        viewed.clear()
        Log.d("DopamineCut", "[$platformName] 시청 기록 캐시 초기화 완료")
    }
}