package com.bernardooechsler.chirp.domain.exception

import com.bernardooechsler.chirp.domain.type.ChatMessageId

class MessageNotFoundException(
    private val id: ChatMessageId
): RuntimeException(
    "Message with ID $id not found"
)