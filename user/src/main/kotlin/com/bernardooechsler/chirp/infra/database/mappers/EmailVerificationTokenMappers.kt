package com.bernardooechsler.chirp.infra.database.mappers

import com.bernardooechsler.chirp.domain.model.EmailVerificationToken
import com.bernardooechsler.chirp.infra.database.entities.EmailVerificationTokenEntity

fun EmailVerificationTokenEntity.toEmailVerificationToken(): EmailVerificationToken {
    return EmailVerificationToken(
        id = id,
        token = token,
        user = user.toUser()
    )
}