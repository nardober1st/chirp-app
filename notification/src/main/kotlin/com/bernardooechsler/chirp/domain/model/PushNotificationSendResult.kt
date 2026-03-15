package com.bernardooechsler.chirp.domain.model

data class PushNotificationSendResult(
    val succeeded: List<DeviceToken>,          // Delivered successfully
    val temporaryFailures: List<DeviceToken>,  // Retry later (server issues)
    val permanentFailures: List<DeviceToken>,  // Token is dead, delete it
)