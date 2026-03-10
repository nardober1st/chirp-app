package com.bernardooechsler.chirp.domain.event

import com.bernardooechsler.chirp.domain.type.UserId

data class ProfilePictureUpdatedEvent(
    val userId: UserId,
    val newUrl: String?
)