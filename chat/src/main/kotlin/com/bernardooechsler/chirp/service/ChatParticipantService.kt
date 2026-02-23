package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.models.ChatParticipant
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.mappers.toChatParticipant
import com.bernardooechsler.chirp.infra.database.mappers.toChatParticipantEntity
import com.bernardooechsler.chirp.infra.database.repositories.ChatParticipantRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Service responsible for managing chat participants — the "profile" representation
 * of a user within the chat system. This is separate from the auth/user service
 * because the chat module only needs a subset of user data (username, email, avatar).
 */
@Service
class ChatParticipantService(
    private val chatParticipantRepository: ChatParticipantRepository,
) {

    /**
     * Persists a new chat participant. Takes a domain model, converts it to a
     * JPA entity for storage. Typically called when a user registers — their
     * chat participant record is created so they can be found and added to chats.
     */
    fun createChatParticipant(
        chatParticipant: ChatParticipant
    ) {
        chatParticipantRepository.save(
            chatParticipant.toChatParticipantEntity()
        )
    }

    /**
     * Looks up a participant by their user ID (primary key lookup).
     * Returns null if not found rather than throwing — lets the caller
     * decide how to handle the missing participant (404, exception, etc.).
     * findByIdOrNull is a Spring Data Kotlin extension that wraps Optional<T>
     * into a nullable T?, which is much more idiomatic in Kotlin.
     */
    fun findChatParticipantById(userId: UserId): ChatParticipant? {
        return chatParticipantRepository.findByIdOrNull(userId)?.toChatParticipant()
    }

    /**
     * Searches for a participant by either email or username using a single query parameter.
     * Normalizes the input (lowercase + trim) to ensure case-insensitive matching —
     * so "John@Email.com" and "john@email.com" both find the same user.
     * The actual OR logic (email OR username) lives in the repository query.
     */
    fun findChatParticipantByEmailOrUsername(
        query: String
    ): ChatParticipant? {
        val normalizedQuery = query.lowercase().trim()
        return chatParticipantRepository.findByEmailOrUsername(
            query = normalizedQuery
        )?.toChatParticipant()
    }
}