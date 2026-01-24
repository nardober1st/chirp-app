package com.bernardooechsler.chirp.api.mappers

import com.bernardooechsler.chirp.api.dto.AuthenticatedUserDto
import com.bernardooechsler.chirp.api.dto.UserDto
import com.bernardooechsler.chirp.domain.model.AuthenticatedUser
import com.bernardooechsler.chirp.domain.model.User

fun User.toUserDto(): UserDto {
    return UserDto(
        id = id,
        email = email,
        username = username,
        hasVerifiedEmail = hasEmailVerified
    )
}

fun AuthenticatedUser.toAuthenticatedUserDto(): AuthenticatedUserDto {
    return AuthenticatedUserDto(
        user = user.toUserDto(),
        accessToken = accessToken,
        refreshToken = refreshToken
    )
}