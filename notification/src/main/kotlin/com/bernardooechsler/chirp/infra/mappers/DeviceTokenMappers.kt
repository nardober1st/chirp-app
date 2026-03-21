package com.bernardooechsler.chirp.infra.mappers

import com.bernardooechsler.chirp.domain.model.DeviceToken
import com.bernardooechsler.chirp.infra.database.DeviceTokenEntity

fun DeviceTokenEntity.toDeviceToken(): DeviceToken {
    return DeviceToken(
        userId = userId,
        token = token,
        platform = platform.toPlatform(),
        createdAt = createdAt,
        id = id
    )
}