package com.bernardooechsler.chirp.infra.database.repositories

import com.bernardooechsler.chirp.infra.database.entities.ChatEntity
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.UserId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ChatRepository: JpaRepository<ChatEntity, ChatId> {

    // Fetch a single chat by ID, but ONLY if the given user is a participant.
    // This combines data fetching + authorization in one query.
    //
    // LEFT JOIN FETCH eagerly loads participants and creator in a SINGLE SQL query.
    // Without it, accessing chat.participants or chat.creator would trigger
    // separate queries (N+1 problem) since they're marked FetchType.LAZY.
    //
    // The EXISTS subquery acts as a security gate — if the user isn't in
    // the chat's participant list, the query returns null instead of the chat.
    // This means the service layer can simply check for null rather than
    // doing a separate "is user authorized?" query.
    @Query("""
        SELECT c
        FROM ChatEntity c
        LEFT JOIN FETCH c.participants
        LEFT JOIN FETCH c.creator
        WHERE c.id = :id
        AND EXISTS (
            SELECT 1
            FROM c.participants p
            WHERE p.userId = :userId
        )
    """)
    fun findChatById(id: ChatId, userId: UserId): ChatEntity?

    // Fetch ALL chats that a user participates in.
    // Powers the chat list screen (the first thing users see).
    //
    // Same LEFT JOIN FETCH pattern as above to avoid N+1.
    // Same EXISTS pattern for filtering — only returns chats where
    // the user is a participant.
    //
    // Performance: the idx (user_id, chat_id) index on the
    // chat_participants_cross_ref join table makes the EXISTS
    // subquery fast — it can look up by user_id first since
    // it's the leftmost column in that index.
    @Query("""
        SELECT c
        FROM ChatEntity c
        LEFT JOIN FETCH c.participants
        LEFT JOIN FETCH c.creator
        WHERE EXISTS (
            SELECT 1
            FROM c.participants p
            WHERE p.userId = :userId
        )
    """)
    fun findAllByUserId(userId: UserId): List<ChatEntity>
}