package com.bernardooechsler.chirp.infra.mappers

import com.bernardooechsler.chirp.domain.model.DeviceToken
import com.bernardooechsler.chirp.infra.database.PlatformEntity

/**
 * Converts domain Platform enum to database Platform enum.
 * Why two enums? Domain model shouldn't depend on JPA/database concerns.
 */
fun DeviceToken.Platform.toPlatformEntity(): PlatformEntity {
    return when (this) {
        DeviceToken.Platform.ANDROID -> PlatformEntity.ANDROID
        DeviceToken.Platform.IOS -> PlatformEntity.IOS
    }
}

/**
 * Converts database Platform enum to domain Platform enum.
 */
fun PlatformEntity.toPlatform(): DeviceToken.Platform {
    return when (this) {
        PlatformEntity.ANDROID -> DeviceToken.Platform.ANDROID
        PlatformEntity.IOS -> DeviceToken.Platform.IOS
    }
}