package com.bernardooechsler.chirp.api.mappers

import com.bernardooechsler.chirp.api.dto.DeviceTokenDto
import com.bernardooechsler.chirp.api.dto.PlatformDto
import com.bernardooechsler.chirp.domain.model.DeviceToken

fun DeviceToken.toDeviceTokenDto(): DeviceTokenDto {
    return DeviceTokenDto(
        userId = userId,
        token = token,
        createdAt = createdAt
    )
}

fun PlatformDto.toPlatform(): DeviceToken.Platform {
    return when (this) {
        PlatformDto.ANDROID -> DeviceToken.Platform.ANDROID
        PlatformDto.IOS -> DeviceToken.Platform.IOS
    }
}