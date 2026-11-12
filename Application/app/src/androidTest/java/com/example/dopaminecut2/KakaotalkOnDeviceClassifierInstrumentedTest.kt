package com.example.dopaminecut2

import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.dopaminecut2.logic.shortform.KakaotalkOnDeviceContentClassifier
import com.example.dopaminecut2.logic.shortform.KakaotalkScreenDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KakaotalkOnDeviceClassifierInstrumentedTest {
    @Test
    fun modelAssetLoadsAndRunsOnAndroid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installed = context.assets.list("models")?.contains("kakaotalk_shortform_state.onnx") == true
        assumeTrue("KakaoTalk 모델이 아직 설치되지 않았습니다 (ml/kakaotalk/export.py --install)", installed)

        // ml/kakaotalk/parity.py와 같은 결정적 패턴. Python ONNX 결과와 로그의 확률을 비교할 수 있다.
        val width = 1080
        val height = 2400
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (255 shl 24) or (((x * 37 + y * 13) % 256) shl 16) or
                (((x * 11 + y * 53) % 256) shl 8) or ((x * 71 + y * 7) % 256)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val classifier = KakaotalkOnDeviceContentClassifier(context)
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val prediction = classifier.classify(bitmap).getOrThrow()
            Log.i("KakaotalkCvCheck", "probabilities=${prediction.probabilities} uiClass=${prediction.uiClass} " +
                "latencyMs=${android.os.SystemClock.elapsedRealtime() - started}")

            assertEquals(KakaotalkScreenDecision.MODEL_CLASSES.toSet(), prediction.probabilities.keys)
            assertTrue(prediction.confidence in 0f..1f)
            assertTrue(prediction.probabilities.values.sum() in 0.999f..1.001f)
        } finally {
            classifier.close()
            bitmap.recycle()
        }
    }
}
