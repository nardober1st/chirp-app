package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.dto.DeviceTokenDto
import com.bernardooechsler.chirp.api.dto.RegisterDeviceRequest
import com.bernardooechsler.chirp.api.mappers.toDeviceTokenDto
import com.bernardooechsler.chirp.api.mappers.toPlatform
import com.bernardooechsler.chirp.api.util.requestUserId
import com.bernardooechsler.chirp.service.PushNotificationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for managing device tokens (push notification registration).
 *
 * Endpoints:
 * - POST /api/notification/register → Register device for push notifications
 * - DELETE /api/notification/{token} → Unregister device (logout)
 */
@RestController
@RequestMapping("/api/notification")
class DeviceTokenController(private val pushNotificationService: PushNotificationService) {

    /**
     * Registers a device token for push notifications.
     * Called by the Android app after FCM gives it a token.
     *
     * The userId comes from the JWT (requestUserId extension property),
     * NOT from the request body — this prevents users from registering
     * tokens for other users.
     */
    @PostMapping("/register")
    fun registerDeviceToken(
        @Valid @RequestBody body: RegisterDeviceRequest
    ): DeviceTokenDto {
        return pushNotificationService.registerDevice(
            userId = requestUserId,  // From JWT, not request body — secure!
            token = body.token,
            platform = body.platformDto.toPlatform()
        ).toDeviceTokenDto()
    }

    /**
     * Unregisters a device token.
     * Called when user explicitly logs out.
     *
     * Token is passed as path variable since it's the identifier.
     * No auth check needed — deleting your own token is harmless,
     * and you'd need to know the exact token to delete someone else's.
     */
    @DeleteMapping("/{token}")
    fun unregisterDeviceToken(
        @PathVariable("token") token: String
    ) {
        pushNotificationService.unregisterDevice(token)
    }
}