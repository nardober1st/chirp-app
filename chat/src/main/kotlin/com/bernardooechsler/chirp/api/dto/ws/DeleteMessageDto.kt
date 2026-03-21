package com.bernardooechsler.chirp.api.dto.ws

import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId

data class DeleteMessageDto(
    val chatId: ChatId,
    val messageId: ChatMessageId
)