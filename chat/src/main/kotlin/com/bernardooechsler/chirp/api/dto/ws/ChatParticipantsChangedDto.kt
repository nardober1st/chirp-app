package com.bernardooechsler.chirp.api.dto.ws

import com.bernardooechsler.chirp.domain.type.ChatId

data class ChatParticipantsChangedDto(
    val chatId: ChatId
)