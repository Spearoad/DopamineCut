package com.example.dopaminecut2

import android.app.Application
import com.example.dopaminecut2.data.remote.FirebaseBootstrap
import com.example.dopaminecut2.di.AppDependencies
import com.example.dopaminecut2.di.AppContainer
import com.example.dopaminecut2.security.AppCheckInstaller
import kotlinx.coroutines.launch

class DopamineCutApplication : Application() {
    @Volatile var isUiVisible = false
        private set
    private var container: AppContainer? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; isUiVisible = started > 0 }
            override fun onActivityStopped(activity: android.app.Activity) { started--; isUiVisible = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        if (FirebaseBootstrap.initialize(this)) {
            AppCheckInstaller.install()
            dependenciesOrNull()?.let { d -> d.applicationScope.launch {
                d.authRepository.authState().collect { uid ->
                    if (uid == null) {
                        com.example.dopaminecut2.notifications.HabitAlarmScheduler.cancel(this@DopamineCutApplication)
                        d.usageSyncSchedule.cancel()
                    } else {
                        com.example.dopaminecut2.notifications.HabitAlarmScheduler.schedule(this@DopamineCutApplication)
                        d.usageSyncSchedule.ensure(uid)
                    }
                }
            } }
        }
    }

    fun dependenciesOrNull(): AppDependencies? {
        if (!FirebaseBootstrap.initialize(this)) return null
        return container ?: AppContainer(this).also { container = it }
    }

    fun requireDependencies(): AppDependencies =
        dependenciesOrNull() ?: error("Firebase 환경이 설정되지 않았습니다.")
}
