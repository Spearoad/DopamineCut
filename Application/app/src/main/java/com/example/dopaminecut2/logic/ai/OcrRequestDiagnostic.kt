package com.example.dopaminecut2.logic.ai

import android.content.Context
import android.util.Log
import com.example.dopaminecut2.BuildConfig
import org.json.JSONObject
import java.io.File

/** Explicit, short-lived local opt-in. Never enabled merely by installing a debug APK. */
object OcrRequestDiagnostic {
    fun record(context: Context, payload: Map<String, Any>) {
        if (!BuildConfig.DEBUG) return
        val until = runCatching { File(context.cacheDir, "ocr-request-diagnostic.until")
            .readText().trim().toLongOrNull() }.getOrNull() ?: return
        val remaining = until - System.currentTimeMillis()
        if (remaining !in 1..30 * 60_000L) return
        val json = JSONObject(payload).toString()
        val chunks = json.chunked(700)
        chunks.forEachIndexed { index, chunk ->
            Log.d("OcrRequestDiagnostic", "event=FUNCTION_REQUEST_ATTEMPT requestId=${payload["requestId"]} " +
                "part=${index + 1}/${chunks.size} json=$chunk")
        }
    }
}
