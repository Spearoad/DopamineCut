package com.example.dopaminecut2.service.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/** Debug-only caller. Observes scheduling without changing recognition decisions or dispatchers. */
class RecognitionResumeTrace : AutoCloseable {
    private val observer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RecognitionTrace").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun event(stage: String, lease: Long) {
        Log.d("RecognitionResumeTrace", "stage=$stage lease=$lease elapsed=${SystemClock.elapsedRealtime()} " +
            "uptime=${SystemClock.uptimeMillis()} tid=${android.os.Process.myTid()} thread=${Thread.currentThread().name}")
    }

    fun beforeResume(lease: Long) {
        val start = SystemClock.elapsedRealtime()
        event("BEFORE_RESUME", lease)
        main.post { event("HANDLER_MARKER_${SystemClock.elapsedRealtime() - start}ms", lease) }
        Dispatchers.Main.immediate.dispatch(EmptyCoroutineContext) {
            event("COROUTINE_MARKER_${SystemClock.elapsedRealtime() - start}ms", lease)
        }
        for (delayMs in listOf(100L, 350L, 1_000L, 1_800L)) {
            observer.schedule({
                val thread = Looper.getMainLooper().thread
                val stack = thread.stackTrace.take(16).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                Log.d("RecognitionResumeTrace", "stage=MAIN_STACK lease=$lease after=${SystemClock.elapsedRealtime() - start}ms " +
                    "state=${thread.state} stack=$stack")
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() { observer.shutdownNow() }
}
