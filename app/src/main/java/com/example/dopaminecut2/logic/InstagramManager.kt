package com.example.dopaminecut2.logic

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * 인스타그램 앱의 릴스(Shortform) 시청 상태 및 광고 여부를 판별하는 매니저 클래스입니다.
 * BaseAppManager를 상속받아 패키지별 공통 규격을 따릅니다.
 */
class InstagramManager : BaseAppManager() {
    override val packageName: String = "com.instagram.android"
    override val platformName: String = "Instagram"

    /** * 1. 릴스(Reels) 섹션 판별
     * 사용자가 현재 '시청 중'인 상태인지 확인합니다.
     * 광고 릴스는 사용자가 선택한 콘텐츠가 아니므로,
     * 통계(시청 횟수/시간)에서 제외하기 위해 광고일 경우 false를 반환합니다.
     */
    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        // [단계 1] 광고 여부 선제적 확인
        // 광고일 경우, 릴스 시청 통계에 합산되지 않도록 즉시 시청 섹션이 아닌 것으로 간주합니다.
        if (isAdContent(rootNode)) {
            return false
        }

        // [단계 2] 릴스 전용 레이아웃 구성 요소 확인
        // 인스타그램의 고유 리소스 ID(좋아요/댓글 버튼 그룹 등)가 화면에 있는지 체크합니다.
        val reelsIds = listOf(
            "com.instagram.android:id/clips_ufi_component",  // 하단 리액션 버튼 영역
            "com.instagram.android:id/clips_tab",            // 하단 릴스 탭 아이콘
            "com.instagram.android:id/clips_video_container" // 릴스 영상 컨테이너
        )
        for (id in reelsIds) {
            if (rootNode.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) {
                return true
            }
        }

        // [단계 3] 텍스트 기반 보조 확인
        // 레이아웃 ID가 바뀌더라도 '릴스'라는 직접적인 문구가 있다면 섹션으로 인정합니다.
        return hasKeywordInTree(rootNode, "릴스")
    }

    /** * 2. 영상 고유 식별자 추출
     * 시청 기록(Record) 중복 방지를 위해 영상의 고유한 ID를 생성합니다.
     * * [추출 방식] 인스타그램의 접근성 설명(Content Description)에 포함된
     * '계정명'을 파싱하여 순수 ID만 추출합니다.
     */
    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        // 'OOO님이 만든 릴스입니다' 문구에서 'OOO' 부분만 분리
        val accountName = findAccountNameFromDesc(rootNode)

        return if (accountName != null) {
            // 접두어 없이 순수 계정명 반환 (예: airbnb, m.m_jyori)
            accountName
        } else {
            // 계정명 추출 실패 시, 화면 내 TextView 중 식별 가능한 텍스트 조합
            val textList = mutableListOf<String>()
            extractTextsFromNode(rootNode, "android.widget.TextView", textList)
            textList.firstOrNull { it.length in 3..20 } ?: "UnknownContent"
        }
    }

    /** * 3. 광고(Ad) 판별
     * 현재 화면의 콘텐츠가 광고(Sponsored)인지 엄격하게 판별합니다.
     */
    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        // [방식 A] 광고 전용 Resource ID 체크
        // 인스타그램 시스템이 광고 전용으로 사용하는 레이아웃 ID를 탐색합니다.
        val strictAdIds = listOf(
            "com.instagram.android:id/clips_single_image_ads_media_content", // 단일 이미지 광고
            "com.instagram.android:id/ads_media_content",                  // 일반 광고 미디어
            "com.instagram.android:id/clips_ads_cta_button"                // 광고 하단 바로가기 버튼
        )
        for (id in strictAdIds) {
            if (rootNode.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) {
                return true
            }
        }

        // [방식 B] 텍스트/설명 정밀 탐색
        // ID로 확인되지 않은 경우, 화면의 모든 노드를 뒤져서 "광고"라는 글자를 찾습니다.
        return hasStrictAdKeyword(rootNode)
    }

    // --- Helper Functions (유틸리티 함수) ---

    /** * 노드 트리를 재귀적으로 탐색하여 '광고' 관련 키워드가
     * 다른 단어와 섞이지 않고 '단독'으로 존재하는지 확인합니다.
     */
    private fun hasStrictAdKeyword(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        // 한국어 "광고", 영어 "Sponsored" 완전 일치 확인
        if (text == "광고" || desc == "광고" || text.equals("Sponsored", ignoreCase = true)) {
            return true
        }
        // 로그에서 발견된 인스타그램 광고 컴포넌트 특수 명칭
        if (desc.contains("ClipsSingleImageAdsComponent")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasStrictAdKeyword(child)) return true
        }
        return false
    }

    /** 화면 전체 노드에서 특정 키워드가 포함되어 있는지 확인하는 함수 */
    private fun hasKeywordInTree(node: AccessibilityNodeInfo, keyword: String): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasKeywordInTree(child, keyword)) return true
        }
        return false
    }

    /** * 접근성 설명(Description)에서 계정명을 추출하는 함수
     * 예: "cocacola_korea님이 만든 릴스입니다." -> "cocacola_korea"
     */
    private fun findAccountNameFromDesc(node: AccessibilityNodeInfo): String? {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains("님이 만든 릴스입니다")) {
            // "님이"를 기준으로 앞부분 문자열만 취함
            return desc.split("님이")[0].trim()
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAccountNameFromDesc(child)
            if (found != null) return found
        }
        return null
    }
}