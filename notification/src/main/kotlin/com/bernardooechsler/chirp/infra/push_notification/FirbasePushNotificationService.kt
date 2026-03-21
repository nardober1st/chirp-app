package com.bernardooechsler.chirp.infra.push_notification

import com.bernardooechsler.chirp.domain.model.DeviceToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.bernardooechsler.chirp.domain.model.PushNotification
import com.bernardooechsler.chirp.domain.model.PushNotificationSendResult
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service

/**
 * Infrastructure service that handles all communication with Firebase Cloud Messaging (FCM).
 * Responsible for initializing the Firebase SDK, validating device tokens, and sending
 * push notifications to Android and iOS devices.
 */
@Service
class FirebasePushNotificationService(
    // Injected from application.yml - path to the Firebase service account JSON file
    // Example: "classpath:firebase-service-account.json"
    @param:Value("\${firebase.credentials-path}")
    private val credentialsPath: String,
    // Spring's resource loader - helps us read files from classpath, filesystem, etc.
    private val resourceLoader: ResourceLoader
) {

    private val logger = LoggerFactory.getLogger(FirebasePushNotificationService::class.java)

    /**
     * Initializes the Firebase Admin SDK when the Spring bean is created.
     * @PostConstruct ensures this runs once after dependency injection is complete,
     * but before the service handles any requests.
     *
     * This loads the service account credentials (downloaded from Firebase Console)
     * and authenticates our backend with Firebase's servers.
     */
    @PostConstruct
    fun initialize() {
        try {
            // Load the JSON credentials file using Spring's resource abstraction
            val serviceAccount = resourceLoader.getResource(credentialsPath)

            // Build Firebase configuration with our service account credentials
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount.inputStream))
                .build()

            // Initialize the singleton FirebaseApp - this can only be called once
            FirebaseApp.initializeApp(options)
            logger.info("Firebase Admin SDK initialized successfully")
        } catch (e: Exception) {
            // If Firebase fails to initialize, the app can't send pushes - fail fast
            logger.error("Error initializing Firebase Admin SDK", e)
            throw e
        }
    }

    /**
     * Validates whether an FCM token is still valid without actually sending a notification.
     * Useful when a client registers a new token - we can verify it's legit before storing.
     *
     * @param token The FCM device token to validate
     * @return true if token is valid, false if it's expired/invalid
     */
    fun isValidToken(token: String): Boolean {
        // Build an empty message - we don't need content for validation
        val message = Message.builder()
            .setToken(token)
            .build()

        return try {
            // The second parameter (true) means "dry run" - Firebase validates
            // the token and message format but doesn't actually deliver anything
            FirebaseMessaging.getInstance().send(message, true)
            true
        } catch (e: FirebaseMessagingException) {
            logger.warn("Failed to validate Firebase token", e)
            false
        }
    }

    /**
     * Sends a push notification to all recipients (potentially multiple devices).
     * Handles platform-specific configuration for Android and iOS automatically.
     *
     * @param notification The notification to send (contains title, message, recipients, etc.)
     * @return Categorized results showing which sends succeeded, failed temporarily, or failed permanently
     */
    fun sendNotification(notification: PushNotification): PushNotificationSendResult {
        // Build one FCM Message object per recipient device
        // Each message is customized for the recipient's platform (Android/iOS)
        val messages = notification.recipients.map { recipient ->
            Message.builder()
                .setToken(recipient.token)
                // The visible notification that appears in the system tray
                .setNotification(
                    Notification.builder()
                        .setTitle(notification.title)
                        .setBody(notification.message)
                        .build()
                )
                .apply {
                    // Attach custom key-value data that the app can read when opened
                    // Useful for deep linking (e.g., "chatId" -> "123" to open specific chat)
                    notification.data.forEach { (key, value) ->
                        putData(key, value)
                    }

                    // Apply platform-specific settings
                    when (recipient.platform) {
                        DeviceToken.Platform.ANDROID -> {
                            setAndroidConfig(
                                AndroidConfig.builder()
                                    // HIGH priority wakes the device immediately instead of batching
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    // Collapse key groups notifications - if multiple messages arrive
                                    // for the same chat while offline, only the latest is shown
                                    .setCollapseKey(notification.chatId.toString())
                                    // Security: only this package can receive these notifications
                                    .setRestrictedPackageName("com.bernardooechsler.chirp")
                                    .build()
                            )
                        }
                        DeviceToken.Platform.IOS -> {
                            setApnsConfig(
                                ApnsConfig.builder()
                                    .setAps(
                                        Aps.builder()
                                            // Play the default iOS notification sound
                                            .setSound("default")
                                            // Thread ID groups notifications in iOS notification center
                                            // All messages from same chat appear together
                                            .setThreadId(notification.chatId.toString())
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                }
                .build()
        }

        // sendEach() sends all messages in a batch and returns individual results for each
        // This is more efficient than calling send() in a loop
        return FirebaseMessaging
            .getInstance()
            .sendEach(messages)
            .toSendResult(notification.recipients)
    }

    /**
     * Extension function that converts Firebase's BatchResponse into our domain model.
     * Categorizes each send result as succeeded, temporary failure, or permanent failure.
     *
     * This categorization is important because:
     * - Succeeded: Nothing to do, notification delivered
     * - Temporary failures: Could retry later (server issues, rate limiting)
     * - Permanent failures: Token is invalid/expired, should be deleted from our database
     *
     * @param allDeviceTokens The original list of recipients (same order as responses)
     * @return Categorized results for upstream handling
     */
    private fun BatchResponse.toSendResult(
        allDeviceTokens: List<DeviceToken>
    ): PushNotificationSendResult {
        val succeeded = mutableListOf<DeviceToken>()
        val temporaryFailures = mutableListOf<DeviceToken>()
        val permanentFailures = mutableListOf<DeviceToken>()

        // Firebase returns responses in the same order as the messages we sent
        // So we can match each response to its corresponding device token by index
        responses.forEachIndexed { index, sendResponse ->
            val deviceToken = allDeviceTokens[index]

            if (sendResponse.isSuccessful) {
                succeeded.add(deviceToken)
            } else {
                val errorCode = sendResponse.exception?.messagingErrorCode
                logger.warn("Failed to send notification to token ${deviceToken.token}: $errorCode")

                when (errorCode) {
                    // PERMANENT FAILURES - these tokens should be deleted from our database
                    // UNREGISTERED: User uninstalled the app or cleared data
                    // SENDER_ID_MISMATCH: Token belongs to a different Firebase project
                    // INVALID_ARGUMENT: Token format is malformed
                    // THIRD_PARTY_AUTH_ERROR: APNs authentication failed (iOS)
                    MessagingErrorCode.UNREGISTERED,
                    MessagingErrorCode.SENDER_ID_MISMATCH,
                    MessagingErrorCode.INVALID_ARGUMENT,
                    MessagingErrorCode.THIRD_PARTY_AUTH_ERROR -> {
                        permanentFailures.add(deviceToken)
                    }
                    // TEMPORARY FAILURES - could retry these later
                    // INTERNAL: Firebase had an internal error
                    // QUOTA_EXCEEDED: We're being rate limited
                    // UNAVAILABLE: FCM servers are temporarily down
                    // null: Unknown error, assume transient
                    MessagingErrorCode.INTERNAL,
                    MessagingErrorCode.QUOTA_EXCEEDED,
                    MessagingErrorCode.UNAVAILABLE,
                    null -> {
                        temporaryFailures.add(deviceToken)
                    }
                }
            }
        }

        logger.debug(
            "Push notifications sent. Succeeded: ${succeeded.size}, " +
                    "temporary failures: ${temporaryFailures.size}, permanent failures: ${permanentFailures.size}"
        )

        // Convert to immutable lists for the result
        return PushNotificationSendResult(
            succeeded = succeeded.toList(),
            temporaryFailures = temporaryFailures.toList(),
            permanentFailures = permanentFailures.toList(),
        )
    }
}