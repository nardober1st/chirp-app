package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.domain.type.UserId
import java.time.Instant

data class DeviceTokenDto(
    val userId: UserId,
    val token: String,
    val createdAt: Instant
)