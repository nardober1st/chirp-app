package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.exception.InvalidDeviceTokenException
import com.bernardooechsler.chirp.domain.model.DeviceToken
import com.bernardooechsler.chirp.domain.model.PushNotification
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.DeviceTokenEntity
import com.bernardooechsler.chirp.infra.database.DeviceTokenRepository
import com.bernardooechsler.chirp.infra.mappers.toDeviceToken
import com.bernardooechsler.chirp.infra.mappers.toPlatformEntity
import com.bernardooechsler.chirp.infra.push_notification.FirebasePushNotificationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Domain service that orchestrates push notification operations.
 * Acts as a bridge between the API layer and the Firebase infrastructure.
 *
 * Responsibilities:
 * - Device token registration/unregistration (managing the device_tokens table)
 * - Building and sending push notifications for chat messages
 * - Retrying temporary failures with exponential backoff
 */
@Service
class PushNotificationService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val firebasePushNotificationService: FirebasePushNotificationService
) {

    companion object {
        // Exponential backoff delays: 30s → 60s → 2min → 5min → 10min
        // Each retry waits longer, giving Firebase time to recover
        private val RETRY_DELAYS_SECONDS = listOf(
            30L,    // 1st retry: wait 30 seconds
            60L,    // 2nd retry: wait 1 minute
            120L,   // 3rd retry: wait 2 minutes
            300L,   // 4th retry: wait 5 minutes
            600L    // 5th retry: wait 10 minutes
        )
        // Don't retry notifications older than 30 minutes — they're stale
        const val MAX_RETRY_AGE_MINUTES = 30L
    }

    /**
     * In-memory queue for scheduled retries.
     *
     * ConcurrentSkipListMap is used because:
     * 1. Keys are sorted (by timestamp) — we can efficiently find "all retries due now"
     * 2. Thread-safe — multiple threads can read/write safely
     * 3. headMap() gives us all entries up to a certain time
     *
     * Structure: { executeAtMillis → [list of retries scheduled for that time] }
     */
    private val retryQueue = ConcurrentSkipListMap<Long, MutableList<RetryData>>()

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Registers a device token for push notifications.
     * Called when a user logs in or when their FCM token refreshes.
     *
     * Handles two scenarios:
     * 1. New token → validate with Firebase, then save
     * 2. Existing token → reassign to current user (device changed hands or re-login)
     *
     * Why reassign existing tokens?
     * If user A logs out and user B logs in on the same device, the FCM token
     * stays the same. We need to point it to user B now.
     *
     * @throws InvalidDeviceTokenException if Firebase rejects the token as invalid
     */
    @Transactional
    fun registerDevice(
        userId: UserId,
        token: String,
        platform: DeviceToken.Platform
    ): DeviceToken {
        val existing = deviceTokenRepository.findByToken(token)

        val trimmedToken = token.trim()

        // Only validate with Firebase if this is a NEW token
        // No need to re-validate tokens we already have stored
        if (existing == null && !firebasePushNotificationService.isValidToken(trimmedToken)) {
            throw InvalidDeviceTokenException()
        }

        val entity = if (existing != null) {
            // Token exists → update ownership to current user
            // This handles the "different user logs into same device" scenario
            deviceTokenRepository.save(
                existing.apply {
                    this.userId = userId
                }
            )
        } else {
            // Brand new token → create fresh record
            deviceTokenRepository.save(
                DeviceTokenEntity(
                    userId = userId,
                    token = trimmedToken,
                    platform = platform.toPlatformEntity()
                )
            )
        }

        return entity.toDeviceToken()
    }

    /**
     * Unregisters a device token.
     * Called when user explicitly logs out (not just closes the app).
     *
     * After this, the device won't receive push notifications until
     * the user logs in again and re-registers their token.
     */
    @Transactional
    fun unregisterDevice(token: String) {
        deviceTokenRepository.deleteByToken(token.trim())
    }

    /**
     * Sends push notifications for a new chat message to all recipients.
     *
     * Flow:
     * 1. Fetch all device tokens for the recipient user IDs
     * 2. Filter out the sender (they don't need a notification for their own message)
     * 3. Build the notification payload with deep-link data
     * 4. Send via Firebase with retry support
     *
     * @param recipientUserIds All users who should potentially receive the notification
     * @param senderUserId The user who sent the message (excluded from notifications)
     * @param senderUsername Display name for the notification title
     * @param message The message content (notification body)
     * @param chatId Used for notification grouping and deep linking
     */
    fun sendNewMessageNotifications(
        recipientUserIds: List<UserId>,
        senderUserId: UserId,
        senderUsername: String,
        message: String,
        chatId: ChatId
    ) {
        // Fetch all device tokens for all potential recipients in one query
        val deviceTokens = deviceTokenRepository.findByUserIdIn(recipientUserIds)

        if (deviceTokens.isEmpty()) {
            logger.info("No device tokens found for $recipientUserIds")
            return
        }

        // Filter out the sender - they shouldn't get notified about their own message
        // Also convert from entity to domain model
        val recipients = deviceTokens
            .filter { it.userId != senderUserId }
            .map { it.toDeviceToken() }

        // Build the notification with data payload for deep linking
        // When user taps the notification, the app knows which chat to open
        val notification = PushNotification(
            title = "New message from $senderUsername",
            recipients = recipients,
            message = message,
            chatId = chatId,
            data = mapOf(
                "chatId" to chatId.toString(),  // For deep linking to specific chat
                "type" to "new_message"          // App can handle different notification types
            )
        )

        // Use sendWithRetry instead of direct send for resilience
        sendWithRetry(notification)
    }

    /**
     * Sends a notification with automatic retry for temporary failures.
     *
     * Flow:
     * 1. Send notification via Firebase
     * 2. Delete tokens that permanently failed (uninstalled apps, etc.)
     * 3. Schedule retry for temporary failures (Firebase overloaded, etc.)
     * 4. Log successes
     *
     * @param notification The notification to send
     * @param attempt Current attempt number (0 = first try, 1 = first retry, etc.)
     */
    fun sendWithRetry(
        notification: PushNotification,
        attempt: Int = 0
    ) {
        val result = firebasePushNotificationService.sendNotification(notification)

        // Permanent failures = dead tokens, remove them from our database
        // These will never work again (user uninstalled, token expired, etc.)
        result.permanentFailures.forEach {
            deviceTokenRepository.deleteByToken(it.token)
        }

        // Temporary failures = might work later, schedule a retry
        // Only retry if we haven't exceeded max attempts
        if (result.temporaryFailures.isNotEmpty() && attempt < RETRY_DELAYS_SECONDS.size) {
            // Create new notification with only the failed recipients
            val retryNotification = notification.copy(
                recipients = result.temporaryFailures
            )
            scheduleRetry(retryNotification, attempt + 1)
        }

        if (result.succeeded.isNotEmpty()) {
            logger.info("Successfully sent notification to ${result.succeeded.size} devices")
        }
    }

    /**
     * Schedules a retry to be processed later.
     *
     * Uses exponential backoff — each retry waits longer:
     * Attempt 1 → wait 30s, Attempt 2 → wait 60s, etc.
     *
     * @param notification The notification to retry (with failed recipients only)
     * @param attempt The attempt number (1 = first retry)
     */
    private fun scheduleRetry(
        notification: PushNotification,
        attempt: Int
    ) {
        // Get delay for this attempt (or use last delay if beyond list)
        val delay = RETRY_DELAYS_SECONDS.getOrElse(attempt - 1) {
            RETRY_DELAYS_SECONDS.last()
        }

        // Calculate when this retry should execute
        val executeAt = Instant.now().plusSeconds(delay)
        val executeAtMillis = executeAt.toEpochMilli()

        val retryData = RetryData(
            notification = notification,
            attempt = attempt,
            createdAt = Instant.now()  // Track when retry was created (for staleness check)
        )

        // Add to retry queue, grouped by execution time
        // compute() is atomic — safe for concurrent access
        retryQueue.compute(executeAtMillis) { _, retries ->
            (retries ?: mutableListOf()).apply { add(retryData) }
        }

        logger.info("Scheduled retry $attempt for ${notification.id} in $delay seconds")
    }

    /**
     * Processes due retries every 15 seconds.
     *
     * @Scheduled makes Spring call this automatically on a background thread.
     * fixedDelay = 15_000L means "wait 15 seconds after the last run completes"
     *
     * Flow:
     * 1. Find all retries scheduled for now or earlier
     * 2. Remove them from the queue
     * 3. Check if they're too old (stale) — drop if so
     * 4. Execute the retry via sendWithRetry()
     */
    @Scheduled(fixedDelay = 15_000L)
    fun processRetries() {
        val now = Instant.now()
        val nowMillis = now.toEpochMilli()

        // headMap gets all entries with keys <= nowMillis (all due retries)
        // inclusive = true means include the exact match
        val toProcess = retryQueue.headMap(nowMillis, true)

        if (toProcess.isEmpty()) {
            return  // Nothing to process
        }

        // Copy entries before iterating (avoid concurrent modification)
        val entries = toProcess.entries.toList()

        entries.forEach { (timeMillis, retries) ->
            // Remove from queue first (so we don't process twice)
            retryQueue.remove(timeMillis)

            retries.forEach { retry ->
                try {
                    // Check if this retry is too old — notifications become stale
                    // A 30-minute-old "new message" notification is useless
                    val age = Duration.between(retry.createdAt, now)
                    if (age.toMinutes() > MAX_RETRY_AGE_MINUTES) {
                        logger.warn("Dropping old retry (${age.toMinutes()} minutes old)")
                        return@forEach  // Skip this retry, continue to next
                    }

                    // Execute the retry (which may schedule another retry if it fails again)
                    sendWithRetry(
                        notification = retry.notification,
                        attempt = retry.attempt
                    )
                } catch (e: Exception) {
                    // Don't let one failed retry crash the whole batch
                    logger.warn("Error processing retry ${retry.notification.id}", e)
                }
            }
        }
    }

    /**
     * Data class holding retry information.
     *
     * @param notification The notification to retry
     * @param attempt Which attempt this is (1 = first retry, 2 = second retry, etc.)
     * @param createdAt When the retry was scheduled (for staleness checks)
     */
    private data class RetryData(
        val notification: PushNotification,
        val attempt: Int,
        val createdAt: Instant
    )
}
//```
//
//---
//
//## Key Concepts Explained
//
//### Why ConcurrentSkipListMap?
//
//| Feature | Why It Matters |
//|---------|----------------|
//| **Sorted keys** | Timestamps as keys, so `headMap(now)` gives all due retries efficiently |
//| **Thread-safe** | Multiple threads can schedule/process retries without locks |
//| **O(log n)** | Fast insertion and lookup even with many pending retries |
//
//**Analogy:** It's like a calendar where you can quickly find "all appointments before 3pm" without scanning every entry.
//
//---
//
//### Exponential Backoff Pattern
//```
//Attempt 0 (first try):  immediate
//Attempt 1 (1st retry):  wait 30 seconds
//Attempt 2 (2nd retry):  wait 60 seconds
//Attempt 3 (3rd retry):  wait 2 minutes
//Attempt 4 (4th retry):  wait 5 minutes
//Attempt 5 (5th retry):  wait 10 minutes
//Attempt 6+:             give up