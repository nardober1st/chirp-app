package com.bernardooechsler.chirp.infra.database

import com.bernardooechsler.chirp.domain.type.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

@Entity
@Table(
    name = "device_tokens",
    schema = "notification_service",  // Separate schema for notification module
    indexes = [
        // Fast lookups when sending pushes to specific users
        Index(name = "idx_device_tokens_user_id", columnList = "user_id"),
        // Ensures one token can't belong to multiple users + fast token lookups
        Index(name = "idx_device_tokens_token", columnList = "token", unique = true),
    ]
)
class DeviceTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(nullable = false)
    var userId: UserId,  // Which user owns this device
    @Column(nullable = false)
    var token: String,   // The FCM token from the device
    @Enumerated(EnumType.STRING)  // Store as "ANDROID" not 0
    @Column(nullable = false)
    var platform: PlatformEntity,
    @CreationTimestamp  // Hibernate auto-sets this on insert
    var createdAt: Instant = Instant.now(),
)