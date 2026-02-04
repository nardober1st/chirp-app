package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.exception.InvalidCredentialsException
import com.bernardooechsler.chirp.domain.exception.InvalidTokenException
import com.bernardooechsler.chirp.domain.exception.SamePasswordException
import com.bernardooechsler.chirp.domain.exception.UserNotFoundException
import com.bernardooechsler.chirp.domain.model.UserId
import com.bernardooechsler.chirp.infra.database.entities.PasswordResetTokenEntity
import com.bernardooechsler.chirp.infra.database.repositories.PasswordResetTokenRepository
import com.bernardooechsler.chirp.infra.database.repositories.RefreshTokenRepository
import com.bernardooechsler.chirp.infra.database.repositories.UserRepository
import com.bernardooechsler.chirp.infra.security.PasswordEncoder
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

// Handles password reset requests (forgot password) and password changes for authenticated users.
// Manages the full lifecycle: token generation, validation, password updates, and session invalidation.
@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    // Configurable expiry time for reset tokens (typically short, e.g., 15-60 minutes)
    @param:Value("\${chirp.email.reset-password.expiry-minutes}")
    private val expiryMinutes: Long,
    private val refreshTokenRepository: RefreshTokenRepository
) {

    // Initiates the "forgot password" flow - generates a reset token and (eventually) sends an email.
    // Silently returns if email doesn't exist to prevent user enumeration attacks.
    @Transactional
    fun requestPasswordReset(email: String) {
        // Security: Don't reveal whether email exists - just return silently if not found
        val user = userRepository.findByEmail(email) ?: return

        // Invalidate any existing reset tokens so only the newest link works
        passwordResetTokenRepository.invalidateActiveTokensForUser(user)

        // Create new token - the entity generates a secure random token automatically
        val token = PasswordResetTokenEntity(
            user = user,
            expiresAt = Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES),
        )
        passwordResetTokenRepository.save(token)

        // TODO: Inform notification service about password reset trigger to send email
    }

    // Completes the password reset flow - validates the token and updates the password.
    // Called when user clicks the reset link and submits their new password.
    @Transactional
    fun resetPassword(token: String, newPassword: String) {
        // Look up the token - throws if not found (invalid or never existed)
        val resetToken = passwordResetTokenRepository.findByToken(token)
            ?: throw InvalidTokenException("Invalid password reset token")

        // Check if token was already used (usedAt != null)
        if (resetToken.isUsed) {
            throw InvalidTokenException("Email verification token is already used.")
        }

        // Check if token has passed its expiration time
        if (resetToken.isExpired) {
            throw InvalidTokenException("Email verification token has already expired.")
        }

        val user = resetToken.user

        // Prevent "resetting" to the same password - compares new password against stored hash
        if (passwordEncoder.matches(newPassword, user.hashedPassword)) {
            throw SamePasswordException()
        }

        // Hash the new password before storing
        val hashedNewPassword = passwordEncoder.encode(newPassword)

        // Update user's password in place and save
        userRepository.save(
            user.apply {
                this.hashedPassword = hashedNewPassword
            }
        )

        // Mark token as used with timestamp (soft invalidation for audit trail)
        passwordResetTokenRepository.save(
            resetToken.apply {
                this.usedAt = Instant.now()
            }
        )

        // Security: Invalidate all existing sessions by deleting refresh tokens.
        // If user is resetting because they were compromised, this logs out the attacker.
        refreshTokenRepository.deleteByUserId(user.id!!)
    }

    // Changes password for an authenticated user who knows their current password.
    // Unlike resetPassword, this requires proving knowledge of the old password first.
    @Transactional
    fun changePassword(
        userId: UserId,
        oldPassword: String,
        newPassword: String,
    ) {
        // Find the user by their ID
        val user = userRepository.findByIdOrNull(userId)
            ?: throw UserNotFoundException()

        // Verify the old password is correct before allowing change
        if (!passwordEncoder.matches(oldPassword, user.hashedPassword)) {
            throw InvalidCredentialsException()
        }

        // Prevent changing to the same password (compares raw passwords here)
        if (oldPassword == newPassword) {
            throw SamePasswordException()
        }

        // Invalidate all sessions - user will need to log in again with new password
        refreshTokenRepository.deleteByUserId(user.id!!)

        // Hash and save the new password
        val newHashedPassword = passwordEncoder.encode(newPassword)
        userRepository.save(
            user.apply {
                this.hashedPassword = newHashedPassword
            }
        )
    }

    // Scheduled cleanup job - runs daily at 3 AM to remove expired tokens.
    // Unlike email verification (7-day retention), reset tokens are deleted immediately
    // upon expiry since they're more security-sensitive.
    @Scheduled(cron = "0 0 3 * * *")
    fun cleanupExpiredTokens() {
        passwordResetTokenRepository.deleteByExpiresAtLessThan(
            now = Instant.now()
        )
    }
}