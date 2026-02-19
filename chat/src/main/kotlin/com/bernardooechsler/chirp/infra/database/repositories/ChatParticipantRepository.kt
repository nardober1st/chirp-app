package com.bernardooechsler.chirp.infra.database.repositories

import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.entities.ChatParticipantEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ChatParticipantRepository: JpaRepository<ChatParticipantEntity, UserId> {

    // Spring Data derived query — Spring auto-generates:
    // SELECT * FROM chat_participants WHERE user_id IN (?, ?, ?)
    // No @Query needed because the method name follows Spring's naming convention.
    // Used when creating a chat: pass a list of user IDs, get back the full entities.
    fun findByUserIdIn(userIds: List<UserId>): Set<ChatParticipantEntity>

    // Case-insensitive search for a participant by exact username or email.
    // Powers the "find someone to chat with" feature.
    // Returns nullable because the user might not exist.
    //
    // Note: LOWER() is applied at query time. The indexes on the entity
    // (idx_chat_participant_username, idx_chat_participant_email) are on the
    // raw columns, so PostgreSQL can't fully use them for LOWER() comparisons.
    // In production, you'd want functional indexes:
    //   CREATE INDEX idx_lower_username ON chat_participants (LOWER(username));
    @Query("""
        SELECT p
        FROM ChatParticipantEntity p
        WHERE LOWER(p.username) = :query OR LOWER(p.email) = :query
    """)
    fun findByEmailOrUsername(query: String): ChatParticipantEntity?
}