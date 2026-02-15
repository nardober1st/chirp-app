package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.events.user.UserEvent
import com.bernardooechsler.chirp.domain.exception.InvalidTokenException
import com.bernardooechsler.chirp.domain.exception.UserNotFoundException
import com.bernardooechsler.chirp.domain.model.EmailVerificationToken
import com.bernardooechsler.chirp.infra.database.entities.EmailVerificationTokenEntity
import com.bernardooechsler.chirp.infra.database.mappers.toEmailVerificationToken
import com.bernardooechsler.chirp.infra.database.mappers.toUser
import com.bernardooechsler.chirp.infra.database.repositories.EmailVerificationTokenRepository
import com.bernardooechsler.chirp.infra.database.repositories.UserRepository
import com.bernardooechsler.chirp.infra.message_queue.EventPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

// Manages the email verification lifecycle: token creation, resend, verification, and cleanup.
@Service
class EmailVerificationService(
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository,
    private val userRepository: UserRepository,
    @param:Value("\${chirp.email.verification.expiry-hours}") private val expiryHours: Long,
    private val eventPublisher: EventPublisher
) {

    // Creates a new token and publishes a resend event to RabbitMQ.
    // Silently returns if the user is already verified — no point sending the email.
    @Transactional
    fun resendVerificationEmail(email: String) {
        val token = createVerificationToken(email)

        if(token.user.hasEmailVerified) {
            return
        }

        eventPublisher.publish(
            event = UserEvent.RequestResendVerification(
                userId = token.user.id,
                email = token.user.email,
                username = token.user.username,
                verificationToken = token.token
            )
        )
    }

    // Invalidates any existing active tokens, then creates a fresh one.
    // This ensures only the most recent verification link works.
    @Transactional
    fun createVerificationToken(email: String): EmailVerificationToken {
        val userEntity = userRepository.findByEmail(email)
            ?: throw UserNotFoundException()

        emailVerificationTokenRepository.invalidateActiveTokensForUser(userEntity)

        val token = EmailVerificationTokenEntity(
            expiresAt = Instant.now().plus(expiryHours, ChronoUnit.HOURS),
            user = userEntity
        )

        return emailVerificationTokenRepository.save(token).toEmailVerificationToken()
    }

    // Validates the token (exists, not used, not expired), marks it as used,
    // then flips the user's verified flag to true.
    @Transactional
    fun verifyEmail(token: String) {
        val verificationToken = emailVerificationTokenRepository.findByToken(token)
            ?: throw InvalidTokenException("Email verification token is invalid.")

        if(verificationToken.isUsed) {
            throw InvalidTokenException("Email verification token is already used.")
        }

        if(verificationToken.isExpired) {
            throw InvalidTokenException("Email verification token has already expired.")
        }

        // Soft invalidation — marks with timestamp instead of deleting for audit trail
        emailVerificationTokenRepository.save(
            verificationToken.apply {
                this.usedAt = Instant.now()
            }
        )
        userRepository.save(
            verificationToken.user.apply {
                this.hasVerifiedEmail = true
            }
        ).toUser()
    }

    // Runs daily at 3 AM — removes expired tokens to keep the table clean
    @Scheduled(cron = "0 0 3 * * *")
    fun cleanupExpiredTokens() {
        emailVerificationTokenRepository.deleteByExpiresAtLessThan(
            now = Instant.now()
        )
    }
}