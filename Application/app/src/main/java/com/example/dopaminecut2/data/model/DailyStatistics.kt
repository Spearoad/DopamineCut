package com.example.dopaminecut2.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

data class DailyStatistics(
    @DocumentId
    var documentId: String = "",                 // 문서 ID ({user_id}_{YYYYMMDD})

    @get:PropertyName("user_id")
    @set:PropertyName("user_id")
    var userId: String = "",

    var date: String = "",                       // 집계 기준 날짜 (예: "20260523")

    @get:PropertyName("daily_score")
    @set:PropertyName("daily_score")
    var dailyScore: Long = 0L,

    @get:PropertyName("shortform_time")
    @set:PropertyName("shortform_time")
    var shortformTime: Long = 0L,

    @get:PropertyName("is_settled")
    @set:PropertyName("is_settled")
    var isSettled: Boolean = false,

    // 🟢 이름표 강력 접착 완료!
    @get:PropertyName("app_usage")
    @set:PropertyName("app_usage")
    var appUsage: Map<String, AppUsage> = emptyMap()
)

data class AppUsage(
    @get:PropertyName("run_time_sec")
    @set:PropertyName("run_time_sec")
    var runTimeSec: Long = 0L,

    @get:PropertyName("shortform_time_sec")
    @set:PropertyName("shortform_time_sec")
    var shortformTimeSec: Long = 0L,

    @get:PropertyName("shortform_count")
    @set:PropertyName("shortform_count")
    var shortformCount: Long = 0L
)