package com.example.dopaminecut2.statistics

import java.security.MessageDigest

/** Stable across retries, including a lost commit acknowledgement. Never contains raw text. */
object UsageSyncIdentity {
    fun payload(snapshot: UsageSnapshot): Map<String, Any> = mapOf(
        "app_usage" to snapshot.appUsage.mapValues { (_, v) -> mapOf("app_time_sec" to v.runTimeSec,
            "shortform_time_sec" to v.shortformTimeSec, "shortform_count" to v.shortformCount) },
        "category_usage" to snapshot.categoryUsage.mapValues { (_, v) -> mapOf("count" to v.count, "duration_sec" to v.durationSec) },
        "hourly_shortform_count" to snapshot.hourlyShortformCount, "deducted_score" to snapshot.deductedScore)

    private fun canonical(value: Any): String = when (value) {
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString("", "{", "}") {
            val key = it.key.toString()
            "${key.length}:$key=${canonical(requireNotNull(it.value))};"
        }
        else -> value.toString()
    }
    fun digest(data: Map<String, *>): String = hash(canonical(data))
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun id(snapshot: UsageSnapshot, data: Map<String, Any>): String {
        val text = "${snapshot.userId}|${snapshot.date}|${snapshot.baseRemoteRevision}|${snapshot.revision}|${canonical(data)}"
        return hash(text)
    }
}

class UsageSyncConflictException : IllegalStateException("서버에 더 최신 기록이 있어 자동 덮어쓰기를 중단했습니다. 기기 기록은 보관됩니다.")
