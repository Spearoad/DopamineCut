package com.example.dopaminecut2.logic

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class TiktokManager : BaseAppManager() {
    /** 해당 매니저가 담당하는 유튜브 앱의 패키지명 */
    // Logcat에서 확인한 틱톡 패키지명
    override val packageName: String = "com.ss.android.ugc.trill"
    /** UI 및 로그 출력에 사용할 플랫폼 이름 */
    override val platformName: String = "Tiktok"

    /** 앱 실행 중일 때 현재 화면이 숏폼 영상인지 확인하는 함수
     * 즉, 숏폼 영상인지 확인할 수 있는 내용을 UI 로그를 통해서 찾아내고, 이를 감지하면 True로 반환하게끔
     */

    // 숏폼 화면 판별, 틱톡의 릴스 플레이어가 되는 player_view 확인
    override fun isShortformSection(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        // 메인 뷰페이저 or 플레이어 뷰 확인
        val hasPlayer = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/player_view").any { isOnScreen(it) }
        val hasViewPager = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/viewpager").any { isOnScreen(it) }

        Log.d("Tiktok_Log", "숏폼 조건 탐지 -> player: $hasPlayer / viewpager: $hasViewPager")

        // 플레이어 or 뷰페이저가 있고, 광고가 아닐 때만 숏폼 시청 체크
        return (hasPlayer || hasViewPager) && !isAdContent(rootNode)
    }

    /** 동일한 숏폼 영상을 시청해도 숏폼 시청으로 카운트되지 않도록 하기 위해
     * 현재 시청중인 숏폼 영상의 고유한 값을 찾아내서 이를 식별자로 사용하는 함수
     * YoutubeManager.kt 에서는 채널명 + 영상 텍스트로 구성하였음.
     */
    override fun getVideoIdentifier(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null

        // 작성자 닉네임 ID (화면 안에 있는 것부터 최우선으로 찾기)
        val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/title")
        var uploader = titleNodes.firstOrNull { isOnScreen(it) }?.text?.toString()
            ?: titleNodes.firstOrNull { it.isVisibleToUser }?.text?.toString()
            ?: titleNodes.firstOrNull()?.text?.toString()

        // 닉네임이 비어있다면 프로필 이미지에서 추출
        if (uploader.isNullOrBlank()) {
            val avatarNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/user_avatar")
            val avatarDesc = avatarNodes.firstOrNull { isOnScreen(it) }?.contentDescription?.toString()
                ?: avatarNodes.firstOrNull()?.contentDescription?.toString()

            if (avatarDesc != null && avatarDesc.contains("님의 프로필")) {
                uploader = avatarDesc.replace(" 님의 프로필", "").trim()
            } else {
                uploader = "Unknown"
            }
        }

        // 쇼츠 본문 ID (화면 안에 있는 것부터 최우선으로 찾기)
        val descNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/desc")
        var description = descNodes.firstOrNull { isOnScreen(it) }?.text?.toString()
            ?: descNodes.firstOrNull { it.isVisibleToUser }?.text?.toString()
            ?: descNodes.firstOrNull()?.text?.toString()
            ?: "NoDesc"

        description = description.replace("…자세히", "").trim()

        // 식별자가 길어지는 것을 방지 (30자로 자르기)
        val safeDescription = if (description.length > 30) description.substring(0, 30) else description

        // 최종적으로 만들어진 식별자 확인
        Log.d("Tiktok_Log", "생성된 식별자: ${uploader}_${safeDescription}")

        return "${uploader}_${safeDescription}"
    }

    /** 현재 시청 중인 숏폼 영상이 광고일 경우 true를 반환하는 함수
     * UI 로그를 통해 현재 시청 중인 내용이 광고인지 확인할 수 있는 내용을 찾아내고, 이를 감지하면 True로 반환하게끔
     */

    override fun isAdContent(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        // 1. 시스템 광고 타이머 텍스트 (5초 후 광고 시작) -> 화면 안에 있을 때만 체크.
        val adTimerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/zw")
        if (adTimerNodes.any { isOnScreen(it) && it.text?.toString()?.contains("광고") == true }) {
            Log.d("Tiktok_Log", "광고 인식됨. (시스템 광고 타이머 발견)")
            return true
        }

        // 2. 추가적인 틱톡 광고 태그 확인 -> 화면 안에 있을 때만 체크.
        val adLabelNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/ad_label")
        if (adLabelNodes.any { isOnScreen(it) }) {
            Log.d("Tiktok_Log", "광고 인식됨. (ad_label 태그 발견)")
            return true
        }

        // 3. 본문 텍스트 내 상업적 단어 체크 -> 화면 안에 있는 본문만 추출
        val descNodes = rootNode.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.trill:id/desc")
        val text = descNodes.firstOrNull { isOnScreen(it) }?.text?.toString() ?: ""

        if (text.contains("광고") || text.contains("구매링크") || text.contains("협찬")) {
            Log.d("Tiktok_Log", "광고 인식됨. (본문 키워드 포함)")
            return true
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

        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenRect = Rect(0, 0, screenWidth, screenHeight)

        return !rect.isEmpty && Rect.intersects(screenRect, rect)
    }
}