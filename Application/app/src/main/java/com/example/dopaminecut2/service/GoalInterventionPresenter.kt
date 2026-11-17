package com.example.dopaminecut2.service

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
import android.os.Build
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.dopaminecut2.R
import com.example.dopaminecut2.domain.*
import com.example.dopaminecut2.logic.*
import com.example.dopaminecut2.main.MainActivity

class GoalInterventionPresenter(private val service: AccessibilityService) {
    private var overlay: View? = null
    val isShowing get() = overlay != null
    private val windows get() = service.getSystemService(WindowManager::class.java)
    fun notify(request: GoalInterventionRequest): Boolean {
        require(request.goal.intervention == InterventionMode.NOTIFY)
        val manager = NotificationManagerCompat.from(service)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) service.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "목표 임박·도달 안내", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 26 && service.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val pending = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java).putExtra("open_goal_settings", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val copy = GoalNoticeText.goal(service, request)
        try {
            manager.notify(request.goal.metric.ordinal + 400, NotificationCompat.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_goal_notification).setContentTitle(copy.title).setContentText(copy.body)
                .setColor(service.getColor(R.color.ui_primary))
                .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            return true
        } catch (_: SecurityException) { return false }
    }
    fun notifyRest(minutes: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(service, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "목표 임박·도달 안내", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(service, 401, Intent(service, MainActivity::class.java).putExtra("open_goal_settings", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val copy = GoalNoticeText.rest(service, minutes)
        return runCatching {
            NotificationManagerCompat.from(service).notify(491, NotificationCompat.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_goal_notification).setContentTitle(copy.title)
                .setContentText(copy.body).setColor(service.getColor(R.color.ui_primary))
                .setContentIntent(tap).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
            true
        }.getOrDefault(false)
    }
    fun show(goal: ManagedGoal, finish: () -> Unit, extend: () -> Unit, settings: () -> Unit) {
        require(goal.intervention == InterventionMode.CONFIRM || goal.intervention == InterventionMode.RESTRICT)
        if (isShowing) return
        val content = GoalInterventionView.create(service, goal, finish, extend, settings).root
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            dimAmount = .38f
            setTitle("DopamineCut goal intervention")
        }
        windows.addView(content, params)
        overlay = content
    }
    fun dismiss() { overlay?.let { runCatching { windows.removeView(it) } }; overlay = null }
    fun clearNotifications() {
        GoalMetric.entries.forEach { NotificationManagerCompat.from(service).cancel(it.ordinal + 400) }
        NotificationManagerCompat.from(service).cancel(491)
    }
    fun clearNotification(metric: GoalMetric) { NotificationManagerCompat.from(service).cancel(metric.ordinal + 400) }
    private companion object { const val CHANNEL = "goal_interventions" }
}
