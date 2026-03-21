package com.bernardooechsler.chirp.domain.model

import com.bernardooechsler.chirp.domain.type.UserId
import java.time.Instant

data class DeviceToken(
    val id: Long,                    // Database primary key
    val userId: UserId,              // Which user owns this device
    val token: String,               // The FCM token (unique per device)
    val platform: Platform,          // ANDROID or IOS
    val createdAt: Instant = Instant.now(),
) {
    enum class Platform {
        ANDROID, IOS
    }
}