package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.events.user.UserEvent
import com.bernardooechsler.chirp.domain.exception.EmailNotVerifiedException
import com.bernardooechsler.chirp.domain.exception.InvalidCredentialsException
import com.bernardooechsler.chirp.domain.exception.InvalidTokenException
import com.bernardooechsler.chirp.domain.exception.UserAlreadyExistsException
import com.bernardooechsler.chirp.domain.exception.UserNotFoundException
import com.bernardooechsler.chirp.domain.model.AuthenticatedUser
import com.bernardooechsler.chirp.domain.model.User
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.entities.RefreshTokenEntity
import com.bernardooechsler.chirp.infra.database.entities.UserEntity
import com.bernardooechsler.chirp.infra.database.mappers.toUser
import com.bernardooechsler.chirp.infra.database.repositories.RefreshTokenRepository
import com.bernardooechsler.chirp.infra.database.repositories.UserRepository
import com.bernardooechsler.chirp.infra.message_queue.EventPublisher
import com.bernardooechsler.chirp.infra.security.PasswordEncoder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

// Handles registration, login, token refresh, and logout.
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val emailVerificationService: EmailVerificationService,
    private val eventPublisher: EventPublisher
) {

    // Registers a new user, creates a verification token, and publishes
    // an event so the notification service sends the verification email.
    @Transactional
    fun register(email: String, username: String, password: String): User {
        val trimmedEmail = email.trim()
        val user = userRepository.findByEmailOrUsername(
            email = trimmedEmail,
            username = username.trim()
        )
        if(user != null) {
            throw UserAlreadyExistsException()
        }

        val savedUser = userRepository.saveAndFlush(
            UserEntity(
                email = trimmedEmail,
                username = username.trim(),
                hashedPassword = passwordEncoder.encode(password)
            )
        ).toUser()

        val token = emailVerificationService.createVerificationToken(trimmedEmail)

        // Publishes to RabbitMQ — notification service picks this up
        // asynchronously and sends the email. If publishing fails,
        // registration still succeeds (user can request resend later).
        eventPublisher.publish(
            event = UserEvent.Created(
                userId = savedUser.id,
                email = savedUser.email,
                username = savedUser.username,
                verificationToken = token.token
            )
        )

        return savedUser
    }

    // Validates credentials, checks email verification, then issues JWT + refresh token.
    fun login(
        email: String,
        password: String
    ): AuthenticatedUser {
        val user = userRepository.findByEmail(email.trim())
            ?: throw InvalidCredentialsException()

        // Same exception for wrong password and unknown email — prevents user enumeration
        if(!passwordEncoder.matches(password, user.hashedPassword)) {
            throw InvalidCredentialsException()
        }

        if(!user.hasVerifiedEmail) {
            throw EmailNotVerifiedException()
        }

        return user.id?.let { userId ->
            val accessToken = jwtService.generateAccessToken(userId)
            val refreshToken = jwtService.generateRefreshToken(userId)

            storeRefreshToken(userId, refreshToken)

            AuthenticatedUser(
                user = user.toUser(),
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        } ?: throw UserNotFoundException()
    }

    // Refresh token rotation: validates the old token, deletes it,
    // then issues a brand new access + refresh token pair.
    @Transactional
    fun refresh(refreshToken: String): AuthenticatedUser {
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw InvalidTokenException(
                message = "Invalid refresh token"
            )
        }

        val userId = jwtService.getUserIdFromToken(refreshToken)
        val user = userRepository.findByIdOrNull(userId)
            ?: throw UserNotFoundException()

        val hashed = hashToken(refreshToken)

        return user.id?.let { userId ->
            // Verify the hashed token exists in the DB (prevents reuse of old tokens)
            refreshTokenRepository.findByUserIdAndHashedToken(
                userId = userId,
                hashedToken = hashed
            ) ?: throw InvalidTokenException("Invalid refresh token")

            // Delete the old token — each refresh token is single-use
            refreshTokenRepository.deleteByUserIdAndHashedToken(
                userId = userId,
                hashedToken = hashed
            )

            val newAccessToken = jwtService.generateAccessToken(userId)
            val newRefreshToken = jwtService.generateRefreshToken(userId)

            storeRefreshToken(userId, newRefreshToken)

            AuthenticatedUser(
                user = user.toUser(),
                accessToken = newAccessToken,
                refreshToken = newRefreshToken
            )
        } ?: throw UserNotFoundException()
    }

    // Deletes the specific refresh token — logs out that single session
    @Transactional
    fun logout(refreshToken: String) {
        val userId = jwtService.getUserIdFromToken(refreshToken)
        val hashed = hashToken(refreshToken)
        refreshTokenRepository.deleteByUserIdAndHashedToken(userId, hashed)
    }

    // Stores a SHA-256 hash of the refresh token (never the raw token)
    private fun storeRefreshToken(userId: UserId, token: String) {
        val hashed = hashToken(token)
        val expiryMs = jwtService.refreshTokenValidityMs
        val expiresAt = Instant.now().plusMillis(expiryMs)

        refreshTokenRepository.save(
            RefreshTokenEntity(
                userId = userId,
                expiresAt = expiresAt,
                hashedToken = hashed
            )
        )
    }

    // SHA-256 hash so we never store raw refresh tokens in the database
    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(hashBytes)
    }
}