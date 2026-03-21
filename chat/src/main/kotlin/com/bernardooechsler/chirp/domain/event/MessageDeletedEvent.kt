package com.bernardooechsler.chirp.domain.event

import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId

data class MessageDeletedEvent(
    val chatId: ChatId,
    val messageId: ChatMessageId,
)