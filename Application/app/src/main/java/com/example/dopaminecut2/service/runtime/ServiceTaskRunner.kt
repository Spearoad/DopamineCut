package com.example.dopaminecut2.service.runtime

import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class TaskHealth(val lastSuccessMs: Long? = null, val failures: Long = 0, val failureType: String? = null)

/** Isolate recoverable work failures without swallowing lifecycle cancellation. No user content is retained. */
class ServiceTaskRunner(private val now: () -> Long, private val onFailure: (String, Throwable) -> Unit) {
    private val health = ConcurrentHashMap<String, TaskHealth>()
    suspend fun run(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        health.compute(name) { _, old -> (old ?: TaskHealth()).copy(lastSuccessMs = now(), failureType = null) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        health.compute(name) { _, old -> (old ?: TaskHealth()).copy(
            failures = (old?.failures ?: 0) + 1, failureType = error.javaClass.simpleName) }
        onFailure(name, error)
        false
    }
    fun snapshot(): Map<String, TaskHealth> = health.toMap()
}

class FailureNoticeLimiter(private val intervalMs: Long = 30_000) {
    private val last = mutableMapOf<String, Long>()
    @Synchronized fun allow(key: String, nowMs: Long): Boolean {
        val previous = last[key]
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        last[key] = nowMs
        return true
    }
}
