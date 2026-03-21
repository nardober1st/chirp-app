package com.bernardooechsler.chirp.domain.model

import com.bernardooechsler.chirp.domain.type.UserId

data class User(
    val id: UserId,
    val username: String,
    val email: String,
    val hasEmailVerified: Boolean
)
