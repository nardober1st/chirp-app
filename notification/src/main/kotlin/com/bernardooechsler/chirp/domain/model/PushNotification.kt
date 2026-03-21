package com.bernardooechsler.chirp.domain.model

import com.bernardooechsler.chirp.domain.type.ChatId
import java.util.UUID

data class PushNotification(
    val id: String = UUID.randomUUID().toString(),  // Unique ID for tracking/logging
    val title: String,                               // "New message from John"
    val recipients: List<DeviceToken>,               // Can send to multiple devices at once
    val message: String,                             // The notification body
    val chatId: ChatId,                              // Which chat this relates to
    val data: Map<String, String>                    // Extra payload (deep linking, etc.)
)