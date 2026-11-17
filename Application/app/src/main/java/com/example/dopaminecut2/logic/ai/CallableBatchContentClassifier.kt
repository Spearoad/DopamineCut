package com.example.dopaminecut2.logic.ai

import com.example.dopaminecut2.domain.SupportedPlatform
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class CallableBatchContentClassifier(
    private val functions: FirebaseFunctions,
    private val currentUserId: () -> String?,
    private val requestDiagnostic: (Map<String, Any>) -> Unit = {}
) : BatchContentClassifier {
    override suspend fun classify(
        batch: List<PendingContentClassification>
    ): Result<List<ClassifiedContent>> = cancellationAwareResult {
        require(batch.isNotEmpty() && batch.size <= 5)
        requireMatchingOwner(batch, currentUserId())
        val requestIds = batch.mapNotNull(PendingContentClassification::requestId).toSet()
        require(requestIds.size <= 1) { "분류 요청 ID가 서로 다릅니다." }
        val requestId = requestIds.singleOrNull() ?: UUID.randomUUID().toString()
        requireSafeId(requestId, "requestId")
        batch.forEach { item ->
            requireSafeId(item.viewSessionId, "viewSessionId")
            requireSafeId(item.contentKey, "contentKey")
            require(item.ocrText.length <= MAX_OCR_TEXT_LENGTH) { "OCR 텍스트가 너무 깁니다." }
            require(item.platform in CLASSIFIABLE_PLATFORMS) { "지원하지 않는 플랫폼입니다." }
        }
        val payload = mapOf(
            "apiVersion" to 2,
            "requestId" to requestId,
            "items" to batch.map { item ->
                mapOf(
                    "viewSessionId" to item.viewSessionId,
                    "contentKey" to item.contentKey,
                    "platform" to item.platform.storageKey,
                    "ocrText" to item.ocrText
                )
            }
        )
        // Recheck the owner immediately before sending sensitive text.
        requireMatchingOwner(batch, currentUserId())
        runCatching { requestDiagnostic(payload) } // Diagnostics must never prevent a request.
        val response = try {
            functions.getHttpsCallable("classifyShortformBatch").call(payload).awaitResult()
        } catch (error: FirebaseFunctionsException) {
            throw error.toClassificationRemoteException()
        }
        requireMatchingOwner(batch, currentUserId())
        CallableBatchResponseParser.parse(response.data, batch, requestId)
    }
}

/**
 * OCR text remains in RAM. The total deadline includes queueing, calls and backoff.
 * Retries reuse the request ID so the server can reuse an already completed result.
 */
class RetryingBatchContentClassifier(
    private val delegate: BatchContentClassifier,
    private val maxAttempts: Int = 2,
    private val retryDelaysMs: List<Long> = listOf(1_000L),
    private val maxInMemoryRequests: Int = 4,
    private val shouldRetry: (Throwable) -> Boolean = ::isTransientFunctionsFailure,
    private val delayBlock: suspend (Long) -> Unit = { delay(it) },
    private val currentUserId: (() -> String?)? = null,
    private val totalTimeoutMs: Long = 45_000L,
    private val callTimeoutMs: Long = 25_000L,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val jitterMs: (Long) -> Long = { base ->
        if (base == 0L) 0L else Random.nextLong(0L, base / 4L + 1L)
    }
) : BatchContentClassifier {
    private val requestMutex = Mutex()
    private val inMemoryRequests = AtomicInteger(0)

    init {
        require(maxAttempts in 1..2)
        require(maxInMemoryRequests > 0)
        require(retryDelaysMs.all { it >= 0L })
        require(totalTimeoutMs > 0L && callTimeoutMs > 0L)
    }

    override suspend fun classify(
        batch: List<PendingContentClassification>
    ): Result<List<ClassifiedContent>> {
        if (!hasValidRequestShape(batch)) return unresolved(batch, "INVALID_REQUEST")
        if (!ownerMatches(batch)) return unresolved(batch, "ACCOUNT_CHANGED")
        val queued = inMemoryRequests.incrementAndGet()
        if (queued > maxInMemoryRequests) {
            inMemoryRequests.decrementAndGet()
            return unresolved(batch, "QUEUE_FULL")
        }
        return try {
            val requestId = requestIdFactory()
            if (!SAFE_ID.matches(requestId)) return unresolved(batch, "INVALID_REQUEST")
            val identifiedBatch = batch.map { it.copy(requestId = requestId) }
            withTimeoutOrNull(totalTimeoutMs) { classifyWithRetry(identifiedBatch) }
                ?: unresolved(batch, "DEADLINE_EXCEEDED")
        } finally {
            inMemoryRequests.decrementAndGet()
        }
    }

    private suspend fun classifyWithRetry(
        batch: List<PendingContentClassification>
    ): Result<List<ClassifiedContent>> {
        for (attempt in 1..maxAttempts) {
            if (!ownerMatches(batch)) return unresolved(batch, "ACCOUNT_CHANGED")
            val result = requestMutex.withLock {
                if (!ownerMatches(batch)) return@withLock Result.failure(AccountChangedException())
                withTimeoutOrNull(callTimeoutMs) {
                    cancellationAwareResult { delegate.classify(batch).getOrThrow() }
                } ?: Result.failure(ClassificationCallTimeoutException())
            }
            if (!ownerMatches(batch)) return unresolved(batch, "ACCOUNT_CHANGED")
            val error = result.exceptionOrNull() ?: return result
            if (error is CancellationException) throw error
            val reason = classificationFailureReason(error)
            // These are terminal even if an injected retry policy says otherwise.
            if (reason == "QUOTA_EXCEEDED" || reason == "ACCOUNT_CHANGED" ||
                attempt == maxAttempts || !shouldRetry(error)
            ) return unresolved(batch, reason)

            val backoff = retryDelaysMs.getOrElse(attempt - 1) { retryDelaysMs.lastOrNull() ?: 0L }
            val retryAfter = (error as? ClassificationProcessingException)?.retryAfterMs ?: 0L
            val waitMs = maxOf(backoff, retryAfter) + jitterMs(backoff).coerceAtLeast(0L)
            // Release the call mutex before backoff so other jobs can make progress.
            delayBlock(waitMs)
        }
        return unresolved(batch, "UNAVAILABLE")
    }

    private fun ownerMatches(batch: List<PendingContentClassification>): Boolean {
        val provider = currentUserId ?: return true // Only omitted by isolated classifier tests.
        val uid = provider() ?: return false
        return uid.isNotBlank() && batch.all { it.ownerUserId == uid }
    }
}

internal class ClassificationProcessingException(val retryAfterMs: Long) :
    IllegalStateException("분류 요청이 아직 처리 중입니다.")

internal class AccountChangedException : IllegalStateException("분류 요청의 계정이 변경되었습니다.")

internal class ClassificationCallTimeoutException : IllegalStateException("분류 응답 대기시간이 초과되었습니다.")

internal class ClassificationRemoteException(
    val reason: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : IllegalStateException(reason, cause)


private fun requireMatchingOwner(batch: List<PendingContentClassification>, currentUid: String?) {
    if (currentUid.isNullOrBlank() || batch.any { it.ownerUserId != currentUid }) throw AccountChangedException()
}

private fun hasValidRequestShape(batch: List<PendingContentClassification>): Boolean =
    batch.size in 1..5 &&
        batch.map(PendingContentClassification::viewSessionId).toSet().size == batch.size &&
        batch.all { item ->
            SAFE_ID.matches(item.viewSessionId) && SAFE_ID.matches(item.contentKey) &&
                item.ocrText.length <= MAX_OCR_TEXT_LENGTH && item.platform in CLASSIFIABLE_PLATFORMS
        }

private fun unresolved(batch: List<PendingContentClassification>, reason: String) =
    Result.success(batch.map { ClassifiedContent.unresolved(it.viewSessionId, reason) })

internal fun classificationFailureReason(error: Throwable): String = when (error) {
    is AccountChangedException -> "ACCOUNT_CHANGED"
    is ClassificationProcessingException -> "PROCESSING"
    is ClassificationCallTimeoutException -> "DEADLINE_EXCEEDED"
    is ClassificationRemoteException -> error.reason
    is IllegalArgumentException -> "INVALID_RESPONSE"
    else -> "UNAVAILABLE"
}

private fun isTransientFunctionsFailure(error: Throwable): Boolean {
    if (error is ClassificationProcessingException || error is ClassificationCallTimeoutException) return true
    return (error as? ClassificationRemoteException)?.retryable == true
}

private fun FirebaseFunctionsException.toClassificationRemoteException(): ClassificationRemoteException {
    val (reason, retryable) = when (code) {
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "QUOTA_EXCEEDED" to false
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "UNAUTHENTICATED" to false
        FirebaseFunctionsException.Code.PERMISSION_DENIED -> "PERMISSION_DENIED" to false
        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> "INVALID_REQUEST" to false
        FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "CONFIGURATION_ERROR" to false
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED" to true
        FirebaseFunctionsException.Code.INTERNAL,
        FirebaseFunctionsException.Code.ABORTED,
        FirebaseFunctionsException.Code.UNAVAILABLE -> "UNAVAILABLE" to true
        else -> "UNAVAILABLE" to false
    }
    return ClassificationRemoteException(reason, retryable, this)
}

private suspend fun <T> cancellationAwareResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private suspend fun Task<HttpsCallableResult>.awaitResult(): HttpsCallableResult =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            val error = task.exception
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(task.result)
        }
    }

private const val MAX_OCR_TEXT_LENGTH = 1_500
private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val CLASSIFIABLE_PLATFORMS = setOf(
    SupportedPlatform.YOUTUBE, SupportedPlatform.INSTAGRAM, SupportedPlatform.KAKAOTALK
)

private fun requireSafeId(value: String, field: String) {
    require(SAFE_ID.matches(value)) { "$field 형식이 잘못되었습니다." }
}
