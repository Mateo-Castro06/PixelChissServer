package com.pixelchiss.auth.dto

import java.time.Instant

data class UserProfileResponse(
    val id: Long,
    val email: String,
    val displayName: String,
    val createdAt: Instant
)