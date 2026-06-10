package com.example.dopaminecut2.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import java.util.Date

data class DopamineLog(
    @DocumentId
    var logId: String = "",                      // 문서 ID (자동 생성)

    @get:PropertyName("user_id")
    @set:PropertyName("user_id")
    var userId: String = "",

    var platform: String = "",

    @get:PropertyName("created_at")
    @set:PropertyName("created_at")
    var createdAt: Date = Date(),

    var category: String = "",                   // OCR 분류 카테고리

    @get:PropertyName("duration_sec")
    @set:PropertyName("duration_sec")
    var durationSec: Long = 0L
)