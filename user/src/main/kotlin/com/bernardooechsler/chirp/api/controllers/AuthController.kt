package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.config.IpRateLimit
import com.bernardooechsler.chirp.api.dto.AuthenticatedUserDto
import com.bernardooechsler.chirp.api.dto.ChangePasswordRequest
import com.bernardooechsler.chirp.api.dto.EmailRequest
import com.bernardooechsler.chirp.api.dto.LoginRequest
import com.bernardooechsler.chirp.api.dto.RefreshRequest
import com.bernardooechsler.chirp.api.dto.RegisterRequest
import com.bernardooechsler.chirp.api.dto.ResetPasswordRequest
import com.bernardooechsler.chirp.api.dto.UserDto
import com.bernardooechsler.chirp.api.mappers.toAuthenticatedUserDto
import com.bernardooechsler.chirp.api.mappers.toUserDto
import com.bernardooechsler.chirp.api.util.requestUserId
import com.bernardooechsler.chirp.infra.rate_limiting.EmailRateLimiter
import com.bernardooechsler.chirp.service.AuthService
import com.bernardooechsler.chirp.service.EmailVerificationService
import com.bernardooechsler.chirp.service.PasswordResetService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val passwordResetService: PasswordResetService,
    private val emailRateLimiter: EmailRateLimiter
) {

    @PostMapping("/register")
    @IpRateLimit(
        requests = 10,
        duration = 1L,
        unit = TimeUnit.HOURS
    )
    fun register(
        @Valid @RequestBody body: RegisterRequest
    ): UserDto {
        return authService.register(
            email = body.email,
            username = body.username,
            password = body.password
        ).toUserDto()
    }

    @PostMapping("/login")
    @IpRateLimit(
        requests = 10,
        duration = 1L,
        unit = TimeUnit.HOURS
    )
    fun login(
        @RequestBody body: LoginRequest
    ): AuthenticatedUserDto {
        return authService.login(
            email = body.email,
            password = body.password
        ).toAuthenticatedUserDto()
    }

    @PostMapping("/refresh")
    fun refresh(
        @RequestBody body: RefreshRequest
    ): AuthenticatedUserDto {
        return authService
            .refresh(body.refreshToken)
            .toAuthenticatedUserDto()
    }

    @PostMapping("/logout")
    fun logout(
        @RequestBody body: RefreshRequest
    ) {
        authService.logout(body.refreshToken)
    }

    @PostMapping("/resend-verification")
    @IpRateLimit(
        requests = 10,
        duration = 1L,
        unit = TimeUnit.HOURS
    )
    fun resendVerification(
        @Valid @RequestBody body: EmailRequest
    ) {
        emailRateLimiter.withRateLimit(
            email = body.email
        ) {
            emailVerificationService.resendVerificationEmail(body.email)
        }
    }

    @GetMapping("/verify")
    fun verifyEmail(
        @RequestParam token: String
    ) {
        emailVerificationService.verifyEmail(token)
    }

    @PostMapping("/forgot-password")
    @IpRateLimit(
        requests = 10,
        duration = 1L,
        unit = TimeUnit.HOURS
    )
    fun forgotPassword(
        @Valid @RequestBody body: EmailRequest
    ) {
        passwordResetService.requestPasswordReset(body.email)
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @Valid @RequestBody body: ResetPasswordRequest
    ) {
        passwordResetService.resetPassword(
            token = body.token,
            newPassword = body.newPassword
        )
    }

    @PostMapping("/change-password")
    fun changePassword(
        @Valid @RequestBody body: ChangePasswordRequest
    ) {
        passwordResetService.changePassword(
            userId = requestUserId,
            oldPassword = body.oldPassword,
            newPassword = body.newPassword
        )
    }
}