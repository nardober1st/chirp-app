package com.bernardooechsler.chirp.infra.database

import com.bernardooechsler.chirp.domain.type.UserId
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceTokenRepository: JpaRepository<DeviceTokenEntity, Long> {
    // Get all device tokens for a list of users (for sending to chat participants)
    fun findByUserIdIn(userIds: List<UserId>): List<DeviceTokenEntity>
    // Find a specific token (for validation or updates)
    fun findByToken(token: String): DeviceTokenEntity?
    // Remove a token (when user logs out or token becomes invalid)
    fun deleteByToken(token: String)
}