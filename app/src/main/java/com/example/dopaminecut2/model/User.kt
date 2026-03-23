package com.example.dopaminecut2.model

import com.google.firebase.Timestamp

data class User(
    val uid: String = "",
    val email: String = "",
    val nickname: String = "",
    val createdAt: Timestamp? = null,
    val isBanned: Boolean = false
)