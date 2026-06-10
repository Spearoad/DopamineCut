package com.example.dopaminecut2.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import java.util.Date

data class User(
    @DocumentId
    var userId: String = "",

    var email: String = "",
    var nickname: String = "",

    @get:PropertyName("created_at")
    @set:PropertyName("created_at")
    var createdAt: Date = Date(),

    // 집중 페널티 태그 (최대 5개)
    var restrictions: List<String> = emptyList(),

    // 기본값 120
    @get:PropertyName("target_time_min")
    @set:PropertyName("target_time_min")
    var targetTimeMin: Int = 120,

    // 기본값 15
    @get:PropertyName("target_shortform_count")
    @set:PropertyName("target_shortform_count")
    var targetCount: Int = 15,

    var inventory: Inventory = Inventory()
)

data class Inventory(
    var poke: Long = 0L,
    var megaphone: Long = 0L
)