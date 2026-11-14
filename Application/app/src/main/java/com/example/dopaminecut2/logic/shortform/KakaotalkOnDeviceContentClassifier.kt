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
 * 카카오톡 4개 화면 클래스 정의 (JSON 메타데이터의 labels 순서, ml/kakaotalk/common.py의 LABELS와 1:1 매핑)
 */
enum class KakaotalkUiClass(val labelName: String) {
    SHORTFORM_NORMAL("SHORTFORM_NORMAL"),
    SHORTFORM_AD("SHORTFORM_AD"),
    NOW_OPENCHAT("NOW_OPENCHAT"),
    CHAT_FRIENDS("CHAT_FRIENDS"),
    UNKNOWN("UNKNOWN");

    /**
     * - SHORTFORM_NORMAL -> NORMAL (시청 시간 및 횟수 집계 / 차단 대상)
     * - SHORTFORM_AD     -> AD (광고이므로 시청 집계 제외)
     * - 숏폼이 아닌 화면 2종(지금 탭 오픈채팅, 친구·채팅) 및 UNKNOWN -> UNKNOWN (집계 제외)
     */
    fun toShortformContentKind(): ShortformContentKind = when (this) {
        SHORTFORM_NORMAL -> ShortformContentKind.NORMAL
        SHORTFORM_AD -> ShortformContentKind.AD
        NOW_OPENCHAT, CHAT_FRIENDS, UNKNOWN -> ShortformContentKind.UNKNOWN
    }

    val isNonShortformScreen: Boolean
        get() = this == NOW_OPENCHAT || this == CHAT_FRIENDS
}

/**
 * 카카오톡 온디바이스 CV 추론 결과 데이터 객체
 */
data class KakaotalkContentPrediction(
    val uiClass: KakaotalkUiClass,
    val contentKind: ShortformContentKind,
    val confidence: Float,
    val probabilities: Map<KakaotalkUiClass, Float>
)

/**
 * 모델 Logit → Softmax 확률 → 클래스별 임계값 확정. Android 의존성이 없어 단위 테스트로 검증한다.
 */
object KakaotalkScreenDecision {
    val MODEL_CLASSES = listOf(
        KakaotalkUiClass.SHORTFORM_NORMAL,
        KakaotalkUiClass.SHORTFORM_AD,
        KakaotalkUiClass.NOW_OPENCHAT,
        KakaotalkUiClass.CHAT_FRIENDS
    )

    fun decide(logits: FloatArray, thresholds: Map<KakaotalkUiClass, Float>): KakaotalkContentPrediction {
        require(logits.size == MODEL_CLASSES.size && logits.all(Float::isFinite))
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - maxLogit).toDouble()) }
        val sum = exps.sum().takeIf { it > 0.0 } ?: 1.0

        val probabilities = MODEL_CLASSES.mapIndexed { idx, uiClass ->
            uiClass to (exps[idx] / sum).toFloat()
        }.toMap()

        val best = probabilities.maxBy { it.value }
        val requiredThreshold = thresholds[best.key] ?: 1f
        val confirmedUiClass = if (best.value >= requiredThreshold) best.key else KakaotalkUiClass.UNKNOWN

        return KakaotalkContentPrediction(
            uiClass = confirmedUiClass,
            contentKind = confirmedUiClass.toShortformContentKind(),
            confidence = best.value,
            probabilities = probabilities
        )
    }
}

/**
 * MobileNetV3-Small ONNX 모델(kakaotalk_shortform_state.onnx)을 사용하여 카카오톡 화면을
 * 4개 클래스(SHORTFORM_NORMAL, SHORTFORM_AD, NOW_OPENCHAT, CHAT_FRIENDS)로 분류합니다.
 * 펑(24시간 스토리)은 학습 데이터가 부족해 클래스에서 제외했다. 펑 화면은 신뢰도 미달(UNKNOWN)로 집계에서 빠지는 것을 기대한다.
 * 학습·변환 도구: Application/ml/kakaotalk
 */
class KakaotalkOnDeviceContentClassifier(
    context: Context
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val thresholds: Map<KakaotalkUiClass, Float>
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
            "KakaoTalk ONNX 모델 체크섬이 metadata.json과 일치하지 않습니다."
        }

        // 3. 라벨 순서 및 입출력 텐서 규격 검증
        val labelsJson = metadata.getJSONArray("labels")
        val expectedLabels = KakaotalkScreenDecision.MODEL_CLASSES.map { it.labelName }
        require((0 until labelsJson.length()).map(labelsJson::getString) == expectedLabels) {
            "KakaoTalk 모델 라벨 구성이 예상과 다릅니다."
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

        // 5. 클래스별 확정 임계값(Decision Thresholds) 로드 (검증셋 정밀도 기준으로 export.py가 산출)
        val thresholdJson = metadata.getJSONObject("decision_thresholds")
        thresholds = KakaotalkScreenDecision.MODEL_CLASSES.associateWith { uiClass ->
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
            require(outputInfo?.shape?.contentEquals(longArrayOf(1, KakaotalkScreenDecision.MODEL_CLASSES.size.toLong())) == true)
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    /**
     * 캡처된 화면 Bitmap을 받아 비동기(Dispatchers.Default)로 4-클래스 추론을 수행합니다.
     */
    suspend fun classify(bitmap: Bitmap): Result<KakaotalkContentPrediction> =
        withContext(Dispatchers.Default) {
            try {
                Result.success(synchronized(sessionLock) {
                    check(!closed) { "KakaoTalk CV 모델이 이미 종료되었습니다." }
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
                            KakaotalkScreenDecision.decide(logits, thresholds)
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
     * Python 학습 코드(ml/kakaotalk/common.py ui_tiles_v1)와 같은 320x320 변환 및 ImageNet 정규화.
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
        const val MODEL_ASSET = "models/kakaotalk_shortform_state.onnx"
        const val METADATA_ASSET = "models/kakaotalk_shortform_state.json"
        const val INPUT_NAME = "screen"
        const val OUTPUT_NAME = "logits"
        const val INPUT_SIZE = 320

        const val RED_MEAN = 0.485f
        const val GREEN_MEAN = 0.456f
        const val BLUE_MEAN = 0.406f
        const val RED_STD = 0.229f
        const val GREEN_STD = 0.224f
        const val BLUE_STD = 0.225f
    }
}
