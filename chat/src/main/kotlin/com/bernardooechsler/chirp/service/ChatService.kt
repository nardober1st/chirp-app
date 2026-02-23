package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.api.dto.ChatMessageDto
import com.bernardooechsler.chirp.api.mappers.toChatMessageDto
import com.bernardooechsler.chirp.domain.exception.ChatNotFoundException
import com.bernardooechsler.chirp.domain.exception.ChatParticipantNotFoundException
import com.bernardooechsler.chirp.domain.exception.ForbiddenException
import com.bernardooechsler.chirp.domain.exception.InvalidChatSizeException
import com.bernardooechsler.chirp.domain.models.Chat
import com.bernardooechsler.chirp.domain.models.ChatMessage
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.infra.database.entities.ChatEntity
import com.bernardooechsler.chirp.infra.database.mappers.toChat
import com.bernardooechsler.chirp.infra.database.repositories.ChatParticipantRepository
import com.bernardooechsler.chirp.infra.database.repositories.ChatRepository
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.mappers.toChatMessage
import com.bernardooechsler.chirp.infra.database.repositories.ChatMessageRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ChatService(
    private val chatRepository: ChatRepository,
    private val chatParticipantRepository: ChatParticipantRepository,
    private val chatMessageRepository: ChatMessageRepository
) {

    /**
     * Fetch messages older than [before] using cursor-based pagination.
     * Results come from DB in descending order (newest first) for efficient querying,
     * then get reversed so the client receives them in chronological order.
     */
    fun getChatMessages(
        chatId: ChatId,
        before: Instant?,
        pageSize: Int
    ): List<ChatMessageDto> {
        return chatMessageRepository
            .findByChatIdBefore(
                chatId = chatId,
                before = before ?: Instant.now(),
                pageable = PageRequest.of(0, pageSize)
            )
            .content
            .asReversed()
            .map { it.toChatMessage().toChatMessageDto() }
    }

    /**
     * Creates a new chat between the creator and one or more other users.
     * Wrapped in @Transactional so all DB operations (lookups + save) either
     * succeed together or roll back entirely — no orphaned/partial state.
     */
    @Transactional
    fun createChat(
        creatorId: UserId,
        otherUserIds: Set<UserId>
    ): Chat {
        // Look up participant entities for each invited user ID.
        // Uses a batch query (IN clause) for efficiency rather than individual lookups.
        // Any IDs that don't exist in the DB are silently excluded from the result.
        val otherParticipants = chatParticipantRepository.findByUserIdIn(
            userIds = otherUserIds
        )

        // Merge found participants with the creator to check total count.
        // A valid chat needs at least 2 people — this catches edge cases like:
        // - Creator trying to chat with only themselves
        // - All provided otherUserIds were invalid / not found in DB
        val allParticipants = (otherParticipants + creatorId)
        if (allParticipants.size < 2) {
            throw InvalidChatSizeException()
        }

        // Fetch the creator's own participant entity separately, since
        // it plays a distinct role on the ChatEntity (tracked as the chat owner).
        // If the creator somehow doesn't exist, fail fast with a clear exception.
        val creator = chatParticipantRepository.findByIdOrNull(creatorId)
            ?: throw ChatParticipantNotFoundException(creatorId)

        // Persist the new chat entity with the creator and all participants,
        // then map the saved JPA entity to a clean domain model (Chat).
        // lastMessage is null because a freshly created chat has no messages yet.
        return chatRepository.save(
            ChatEntity(
                creator = creator,
                participants = setOf(creator) + otherParticipants
            )
        ).toChat(lastMessage = null)
    }

    @Transactional
    fun addParticipantsToChat(
        requestUserId: UserId,
        chatId: ChatId,
        userIds: Set<UserId>
    ): Chat {
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ChatNotFoundException()

        val isRequestingUserInChat = chat.participants.any {
            it.userId == requestUserId
        }
        if(!isRequestingUserInChat) {
            throw ForbiddenException()
        }

        val users = userIds.map { userId ->
            chatParticipantRepository.findByIdOrNull(userId)
                ?: throw ChatParticipantNotFoundException(userId)
        }

        val lastMessage = lastMessageForChat(chatId)
        val updatedChat = chatRepository.save(
            chat.apply {
                this.participants = chat.participants + users
            }
        ).toChat(lastMessage)

        return updatedChat
    }

    @Transactional
    fun removeParticipantFromChat(
        chatId: ChatId,
        userId: UserId
    ) {
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ChatNotFoundException()
        val participant = chat.participants.find { it.userId == userId }
            ?: throw ChatParticipantNotFoundException(userId)

        val newParticipantsSize = chat.participants.size - 1
        if(newParticipantsSize == 0) {
            chatRepository.deleteById(chatId)
            return
        }

        chatRepository.save(
            chat.apply {
                this.participants = chat.participants - participant
            }
        )
    }

    private fun lastMessageForChat(chatId: ChatId): ChatMessage? {
        return chatMessageRepository
            .findLatestMessagesByChatIds(setOf(chatId))
            .firstOrNull()
            ?.toChatMessage()
    }
}