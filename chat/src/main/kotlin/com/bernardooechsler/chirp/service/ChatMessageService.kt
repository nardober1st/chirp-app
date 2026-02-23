package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.exception.ChatNotFoundException
import com.bernardooechsler.chirp.domain.exception.ChatParticipantNotFoundException
import com.bernardooechsler.chirp.domain.exception.ForbiddenException
import com.bernardooechsler.chirp.domain.exception.MessageNotFoundException
import com.bernardooechsler.chirp.domain.models.ChatMessage
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.entities.ChatMessageEntity
import com.bernardooechsler.chirp.infra.database.mappers.toChatMessage
import com.bernardooechsler.chirp.infra.database.repositories.ChatMessageRepository
import com.bernardooechsler.chirp.infra.database.repositories.ChatParticipantRepository
import com.bernardooechsler.chirp.infra.database.repositories.ChatRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChatMessageService(
    private val chatRepository: ChatRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatParticipantRepository: ChatParticipantRepository
) {

    /**
     * Persist a new message in the given chat.
     * [messageId] can be provided by the client for idempotency on retries.
     * Validates that the chat exists and the sender is a participant.
     */
    @Transactional
    fun sendMessage(
        chatId: ChatId,
        senderId: UserId,
        content: String,
        messageId: ChatMessageId? = null
    ): ChatMessage {
        val chat = chatRepository.findChatById(chatId, senderId)
            ?: throw ChatNotFoundException()
        val sender = chatParticipantRepository.findByIdOrNull(senderId)
            ?: throw ChatParticipantNotFoundException(senderId)

        val savedMessage = chatMessageRepository.save(
            ChatMessageEntity(
                id = messageId,
                content = content.trim(),
                chatId = chatId,
                chat = chat,
                sender = sender
            )
        )

        return savedMessage.toChatMessage()
    }

    /**
     * Hard-delete a message. Only the original sender can delete their own messages.
     */
    @Transactional
    fun deleteMessage(
        messageId: ChatMessageId,
        requestUserId: UserId
    ) {
        val message = chatMessageRepository.findByIdOrNull(messageId)
            ?: throw MessageNotFoundException(messageId)

        if (message.sender.userId != requestUserId) {
            throw ForbiddenException()
        }

        chatMessageRepository.delete(message)
    }
}