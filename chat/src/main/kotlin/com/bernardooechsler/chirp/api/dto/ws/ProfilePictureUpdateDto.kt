package com.bernardooechsler.chirp.api.dto.ws

import com.bernardooechsler.chirp.domain.type.UserId

data class ProfilePictureUpdateDto(
    val userId: UserId,
    val newUrl: String?
)