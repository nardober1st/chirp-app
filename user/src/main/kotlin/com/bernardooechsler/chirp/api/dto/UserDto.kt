package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.domain.model.UserId

data class UserDto (
    val id: UserId,
    val email: String,
    val username: String,
    val hasVerifiedEmail: Boolean
)
