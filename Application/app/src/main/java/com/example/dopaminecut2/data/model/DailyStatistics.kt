package com.example.dopaminecut2.data.model

import kotlin.math.max

/** 월별 Firestore 문서 안에 포함되는 하루 집계 값. */
data class DailyStatistics(
    val date: String = "",
    val deductedScore: Long = 0L,
    val appUsage: Map<String, AppUsage> = emptyMap(),
    val categoryUsage: Map<String, CategoryUsage> = emptyMap(),
    val hourlyShortformCount: Map<String, Long> = emptyMap(),
    val remoteRevision: Long = 0L
) {
    val dailyScore: Long get() = max(0L, 100L - deductedScore)
    val totalAppTimeSec: Long get() = appUsage.values.sumOf(AppUsage::runTimeSec)
    val totalShortformTimeSec: Long get() = appUsage.values.sumOf(AppUsage::shortformTimeSec)
    val totalShortformCount: Long get() = appUsage.values.sumOf(AppUsage::shortformCount)
}

data class AppUsage(
    val runTimeSec: Long = 0L,
    val shortformTimeSec: Long = 0L,
    val shortformCount: Long = 0L,
    val hourlyShortformTimeSec: Map<String, Long> = emptyMap(),
    val hourlyShortformCount: Map<String, Long> = emptyMap()
)

data class CategoryUsage(
    val count: Long = 0L,
    val durationSec: Long = 0L
)