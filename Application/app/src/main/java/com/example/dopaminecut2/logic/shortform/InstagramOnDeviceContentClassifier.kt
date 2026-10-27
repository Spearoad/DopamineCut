package com.example.dopaminecut2.logic.shortform

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp

/**
 * 인스타그램 5개 화면 클래스 정의 (JSON 메타데이터의 labels 순서와 1:1 매핑)
 */
enum class InstagramUiClass(val labelName: String) {
    REEL_NORMAL("REEL_NORMAL"),
    REEL_AD("REEL_AD"),
    FEED_SEARCH("FEED_SEARCH"),
    PROFILE_DM("PROFILE_DM"),
    STORY("STORY"),
    UNKNOWN("UNKNOWN");

    /**
     * 앱의 기존 도메인 모델(ShortformContentKind)로 변환합니다.
     * - REEL_NORMAL -> NORMAL (시청 시간 및 횟수 집계 / 차단 대상)
     * - REEL_AD     -> AD (광고이므로 시청 집계 제외)
     * - 안전 구역 3종(FEED_SEARCH, PROFILE_DM, STORY) 및 UNKNOWN -> UNKNOWN (비숏폼 처리하여 집계 제외)
     */
    fun toShortformContentKind(): ShortformContentKind = when (this) {
        REEL_NORMAL -> ShortformContentKind.NORMAL
        REEL_AD -> ShortformContentKind.AD
        FEED_SEARCH, PROFILE_DM, STORY, UNKNOWN -> ShortformContentKind.UNKNOWN
    }

    val isSafeNonReelScreen: Boolean
        get() = this == FEED_SEARCH || this == PROFILE_DM || this == STORY
}

/**
 * 인스타그램 온디바이스 CV 추론 결과 데이터 객체
 */
data class InstagramContentPrediction(
    val uiClass: InstagramUiClass,
    val contentKind: ShortformContentKind,
    val confidence: Float,
    val probabilities: Map<InstagramUiClass, Float>
)

/**
 * MobileNetV3-Small ONNX 모델(instagram_shortform_state.onnx)을 사용하여
 * 인스타그램 화면을 5개 클래스(REEL_NORMAL, REEL_AD, FEED_SEARCH, PROFILE_DM, STORY)로 분류합니다.
 */
class InstagramOnDeviceContentClassifier(
    context: Context
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val thresholds: Map<InstagramUiClass, Float>
    private val sessionLock = Any()
    private var closed = false

    init {
        // 1. 모델 바이트 및 JSON 메타데이터 로드
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val metadata = context.assets.open(METADATA_ASSET).bufferedReader().use {
            JSONObject(it.readText())
        }

        // 2. SHA-256 체크섬 무결성 검증
        val hash = MessageDigest.getInstance("SHA-256").digest(modelBytes)
            .joinToString("") { "%02x".format(it) }
        require(hash == metadata.getString("sha256")) {
            "Instagram ONNX 모델 체크섬이 metadata.json과 일치하지 않습니다."
        }

        // 3. 라벨 순서 및 입출력 텐서 규격 검증
        val labelsJson = metadata.getJSONArray("labels")
        val expectedLabels = MODEL_CLASSES.map { it.labelName }
        require((0 until labelsJson.length()).map(labelsJson::getString) == expectedLabels) {
            "Instagram 모델 라벨 구성이 예상과 다릅니다."
        }
        require(metadata.getString("input_name") == INPUT_NAME)
        val shape = metadata.getJSONArray("input_shape")
        require((0 until shape.length()).map(shape::getInt) == listOf(1, 3, INPUT_SIZE, INPUT_SIZE))

        // 4. WholeUiTiles (ui_tiles_v1) 전처리 규격 일치 여부 검증
        val preprocessing = metadata.getJSONObject("preprocessing")
        require(preprocessing.getString("ui_view_mode") == "ui_tiles_v1")
        require(preprocessing.getString("experiment") == "whole_ui_v1")
        require(preprocessing.getString("layout") == "NCHW" && preprocessing.getString("color") == "RGB")
        require(preprocessing.getString("tile_resize") == "fit_bilinear")

        val tiles = preprocessing.getJSONArray("roi_tiles")
        require(tiles.length() == WholeUiTiles.sources.size)
        for (i in 0 until tiles.length()) {
            val source = tiles.getJSONObject(i).getJSONArray("source_ratio")
            val destination = tiles.getJSONObject(i).getJSONArray("destination_px")
            require((0..3).all { abs(source.getDouble(it) - WholeUiTiles.sources[i][it]) < 1e-9 })
            require((0..3).all { destination.getInt(it) == WholeUiTiles.destinations[i][it] })
        }

        // 5. 클래스별 확정 임계값(Decision Thresholds) 로드
        val thresholdJson = metadata.getJSONObject("decision_thresholds")
        thresholds = MODEL_CLASSES.associateWith { uiClass ->
            thresholdJson.getDouble(uiClass.labelName).toFloat().coerceIn(0f, 1f)
        }

        // 6. ONNX Runtime 세션 생성 (모바일 CPU 스레드 2개 할당)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(modelBytes, options)
        }

        try {
            val inputInfo = session.inputInfo[INPUT_NAME]?.info as? TensorInfo
            require(inputInfo?.shape?.contentEquals(longArrayOf(1, 3, 320, 320)) == true)
            val outputInfo = session.outputInfo[OUTPUT_NAME]?.info as? TensorInfo
            require(outputInfo?.shape?.contentEquals(longArrayOf(1, 5)) == true)
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    /**
     * 캡처된 화면 Bitmap을 받아 비동기(Dispatchers.Default)로 5-클래스 추론을 수행합니다.
     */
    suspend fun classify(bitmap: Bitmap): Result<InstagramContentPrediction> =
        withContext(Dispatchers.Default) {
            try {
                Result.success(synchronized(sessionLock) {
                    check(!closed) { "Instagram CV 모델이 이미 종료되었습니다." }
                    require(bitmap.height > bitmap.width) { "세로 화면 캡처본만 지원합니다." }

                    val inputBuffer = preprocess(bitmap)
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(inputBuffer),
                        longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                    ).use { tensor ->
                        session.run(mapOf(INPUT_NAME to tensor)).use { outputs ->
                            @Suppress("UNCHECKED_CAST")
                            val logits = (outputs[0].value as Array<FloatArray>).single()
                            decideFromLogits(logits)
                        }
                    }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    /**
     * 모델이 출력한 5개의 Logit 값을 Softmax 확률(0.0~1.0)로 변환하고,
     * 각 클래스의 임계값(Threshold)을 넘었을 때만 최종 상태를 확정합니다.
     */
    private fun decideFromLogits(logits: FloatArray): InstagramContentPrediction {
        require(logits.size == MODEL_CLASSES.size && logits.all(Float::isFinite))
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - maxLogit).toDouble()) }
        val sum = exps.sum().takeIf { it > 0.0 } ?: 1.0

        val probabilities = MODEL_CLASSES.mapIndexed { idx, uiClass ->
            uiClass to (exps[idx] / sum).toFloat()
        }.toMap()

        val best = probabilities.maxBy { it.value }
        val requiredThreshold = thresholds[best.key] ?: 1f
        val confirmedUiClass = if (best.value >= requiredThreshold) {
            best.key
        } else {
            InstagramUiClass.UNKNOWN
        }

        return InstagramContentPrediction(
            uiClass = confirmedUiClass,
            contentKind = confirmedUiClass.toShortformContentKind(),
            confidence = best.value,
            probabilities = probabilities
        )
    }

    /**
     * 코랩 학습 코드와 100% 동일한 WholeUiTiles(320x320) 변환 및 ImageNet 정규화를 수행합니다.
     */
    private fun preprocess(source: Bitmap): FloatArray {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val tiledPixels = WholeUiTiles.resize(pixels, source.width, source.height)
        return normalizePixels(tiledPixels)
    }

    private fun normalizePixels(pixels: IntArray): FloatArray {
        val planeSize = pixels.size
        return FloatArray(planeSize * 3).also { output ->
            pixels.forEachIndexed { index, color ->
                output[index] = (((color shr 16) and 0xFF) / 255f - RED_MEAN) / RED_STD
                output[planeSize + index] = (((color shr 8) and 0xFF) / 255f - GREEN_MEAN) / GREEN_STD
                output[planeSize * 2 + index] = ((color and 0xFF) / 255f - BLUE_MEAN) / BLUE_STD
            }
        }
    }

    override fun close() {
        synchronized(sessionLock) {
            if (!closed) {
                session.close()
                closed = true
            }
        }
    }

    private companion object {
        const val MODEL_ASSET = "models/instagram_shortform_state.onnx"
        const val METADATA_ASSET = "models/instagram_shortform_state.json"
        const val INPUT_NAME = "screen"
        const val OUTPUT_NAME = "logits"
        const val INPUT_SIZE = 320

        const val RED_MEAN = 0.485f
        const val GREEN_MEAN = 0.456f
        const val BLUE_MEAN = 0.406f
        const val RED_STD = 0.229f
        const val GREEN_STD = 0.224f
        const val BLUE_STD = 0.225f

        val MODEL_CLASSES = listOf(
            InstagramUiClass.REEL_NORMAL,
            InstagramUiClass.REEL_AD,
            InstagramUiClass.FEED_SEARCH,
            InstagramUiClass.PROFILE_DM,
            InstagramUiClass.STORY
        )
    }
}