package com.bernardooechsler.chirp.domain.models

import com.bernardooechsler.chirp.domain.type.UserId

data class ChatParticipant(
    val userId: UserId,
    val username: String,
    val email: String,
    val profilePictureUrl: String?
)