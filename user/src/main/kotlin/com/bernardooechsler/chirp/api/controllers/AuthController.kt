package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.dto.AuthenticatedUserDto
import com.bernardooechsler.chirp.api.dto.LoginRequest
import com.bernardooechsler.chirp.api.dto.RefreshRequest
import com.bernardooechsler.chirp.api.dto.RegisterRequest
import com.bernardooechsler.chirp.api.dto.UserDto
import com.bernardooechsler.chirp.api.mappers.toAuthenticatedUserDto
import com.bernardooechsler.chirp.api.mappers.toUserDto
import com.bernardooechsler.chirp.service.auth.AuthService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
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
}