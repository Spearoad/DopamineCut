package com.example.dopaminecut2.service.runtime

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.dopaminecut2.logic.manager.ScreenSnapshot

data class ForegroundSnapshot(val packageName: String?, val snapshot: ScreenSnapshot?,
    val interactive: Boolean, val complete: Boolean = true)

/** No live accessibility node crosses this reader's thread boundary. */
class AccessibilitySnapshotReader(
    private val service: AccessibilityService,
    private val power: PowerManager,
    private val keyguard: KeyguardManager,
    private val allowedPackages: Set<String>
) {
    fun read(): ForegroundSnapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "UI tree reads must not run on Main" }
        if (!power.isInteractive || keyguard.isKeyguardLocked) return ForegroundSnapshot(null, null, false)
        val root = service.rootInActiveWindow ?: return ForegroundSnapshot(null, null, true)
        try {
            val name = root.packageName?.toString()
            if (name !in allowedPackages) return ForegroundSnapshot(name, null, true)
            val started = SystemClock.elapsedRealtime()
            val snapshot = ScreenSnapshot.from(root) { SystemClock.elapsedRealtime() - started < 800 }
            return ForegroundSnapshot(name, snapshot, true, snapshot.complete)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }
}
