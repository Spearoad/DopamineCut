package com.example.dopaminecut2.model

data class DailyRecord(
    val uid: String = "",
    val date: String = "", // 형식: "yyyy-MM-dd"
    val totalUsageTime: Long = 0,
    val isGoalAchieved: Boolean = false,
    val appUsageTime: Map<String, Long> = mapOf(),
    val appUsageCount: Map<String, Int> = mapOf()
)