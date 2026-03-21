package com.bernardooechsler.chirp.api.dto

import jakarta.validation.constraints.NotBlank

data class RegisterDeviceRequest(
    @field:NotBlank
    val token: String,
    val platformDto: PlatformDto // Phillip wrote val platform
)

enum class PlatformDto {
    ANDROID, IOS
}