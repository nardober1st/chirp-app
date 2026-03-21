package com.bernardooechsler.chirp.api.dto.ws

import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId

data class SendMessageDto(
    val chatId: ChatId,
    val content: String,
    val messageId: ChatMessageId? = null
)