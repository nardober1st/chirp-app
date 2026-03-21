package com.bernardooechsler.chirp.infra.database.mappers

import com.bernardooechsler.chirp.domain.model.User
import com.bernardooechsler.chirp.infra.database.entities.UserEntity

fun UserEntity.toUser(): User {
    return User(
        id = id!!,
        username = username,
        email = email,
        hasEmailVerified = hasVerifiedEmail
    )
}