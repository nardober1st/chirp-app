package com.bernardooechsler.chirp.domain.events.user

import com.bernardooechsler.chirp.domain.events.ChirpEvent
import com.bernardooechsler.chirp.domain.type.UserId
import java.time.Instant
import java.util.UUID

// Sealed class hierarchy representing all user-related domain events.
// Sealed ensures exhaustive handling on the consumer side - the compiler
// forces you to handle every event type in `when` expressions.
// Lives in the `common` module so both `user` (producer) and `notification` (consumer)
// modules can share these types without coupling to each other.
sealed class UserEvent(
    // Unique ID per event instance - useful for idempotency checks and audit trails
    override val eventId: String = UUID.randomUUID().toString(),
    // All user events route to the same RabbitMQ exchange ("user.events")
    override val exchange: String = UserEventConstants.USER_EXCHANGE,
    // Timestamp of when the event was created, not when it was published or consumed
    override val occurredAt: Instant = Instant.now(),
): ChirpEvent {

    // Fired after successful user registration.
    // Carries the verification token so the notification service can
    // construct the email verification link without querying the database.
    data class Created(
        val userId: UserId,
        val email: String,
        val username: String,
        val verificationToken: String,
        // Routing key "user.created" - allows topic exchange binding with wildcards like "user.*"
        override val eventKey: String = UserEventConstants.USER_CREATED_KEY
    ): UserEvent(), ChirpEvent

    // Fired after a user successfully verifies their email.
    // No token needed here - this is just a confirmation/welcome email trigger.
    data class Verified(
        val userId: UserId,
        val email: String,
        val username: String,
        override val eventKey: String = UserEventConstants.USER_VERIFIED
    ): UserEvent(), ChirpEvent

    // Fired when a user requests a new verification email.
    // Includes the newly generated token (old one was invalidated).
    data class RequestResendVerification(
        val userId: UserId,
        val email: String,
        val username: String,
        val verificationToken: String,
        override val eventKey: String = UserEventConstants.USER_REQUEST_RESEND_VERIFICATION
    ): UserEvent(), ChirpEvent

    // Fired when a user requests a password reset ("forgot password" flow).
    // Includes expiresInMinutes so the email body can tell the user
    // how long their reset link remains valid (e.g., "expires in 15 minutes").
    data class RequestResetPassword(
        val userId: UserId,
        val email: String,
        val username: String,
        val verificationToken: String,
        val expiresInMinutes: Long,
        override val eventKey: String = UserEventConstants.USER_REQUEST_RESET_PASSWORD
    ): UserEvent(), ChirpEvent
}