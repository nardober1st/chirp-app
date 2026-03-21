package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.domain.type.UserId
import jakarta.validation.constraints.Size

data class AddParticipantToChatDto(
    @field:Size(min = 1)
    val userIds: List<UserId>
)