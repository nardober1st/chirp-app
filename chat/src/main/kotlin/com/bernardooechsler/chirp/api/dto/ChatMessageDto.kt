package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId
import com.bernardooechsler.chirp.domain.type.UserId
import java.time.Instant

data class ChatMessageDto(
    val id: ChatMessageId,
    val chatId: ChatId,
    val content: String,
    val createdAt: Instant,
    val senderId: UserId
)