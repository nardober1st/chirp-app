package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.domain.type.UserId

data class ChatParticipantDto(
    val userId: UserId,
    val username: String,
    val email: String,
    val profilePictureUrl: String?
)