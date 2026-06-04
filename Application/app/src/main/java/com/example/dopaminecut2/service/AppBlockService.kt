package com.example.dopaminecut2.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.dopaminecut2.logic.ViewTracker
import com.example.dopaminecut2.logic.manager.AppManagerInterface
import com.example.dopaminecut2.logic.manager.InstagramManager
import com.example.dopaminecut2.logic.manager.KakaotalkManager
import com.example.dopaminecut2.logic.manager.TiktokManager
import com.example.dopaminecut2.logic.manager.YoutubeManager

class AppBlockService : AccessibilityService() {

    private val appManagers: List<AppManagerInterface> = listOf(
        YoutubeManager(),
        TiktokManager(),
        InstagramManager(),
        KakaotalkManager()
    )

    private var activeManager: AppManagerInterface? = null

    private val viewTracker = ViewTracker { platform, videoId ->
        Log.d(TAG, "유효 숏폼 시청: platform=$platform, videoId=$videoId")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AppBlockService 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val manager = appManagers.find { it.packageName == packageName }

        if (manager == null) {
            if (activeManager != null) {
                activeManager = null
                viewTracker.onScreenChanged(false, null, false, "")
            }
            return
        }

        activeManager = manager

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            val isShortform = manager.isShortformSection(root)
            val videoId = manager.getVideoIdentifier(root)
            val isAd = manager.isAdContent(root)
            viewTracker.onScreenChanged(isShortform, videoId, isAd, manager.platformName)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppBlockService interrupted")
    }

    companion object {
        private const val TAG = "AppBlockService"
    }
}
