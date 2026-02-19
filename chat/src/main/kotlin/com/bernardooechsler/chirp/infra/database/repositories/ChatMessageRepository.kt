package com.bernardooechsler.chirp.infra.database.repositories

import com.bernardooechsler.chirp.infra.database.entities.ChatMessageEntity
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ChatMessageRepository: JpaRepository<ChatMessageEntity, ChatMessageId> {

    // Cursor-based pagination: fetch messages in a chat OLDER than the cursor timestamp.
    // Returns a Slice (not Page) — a Slice only knows "is there a next batch?"
    // without running an expensive COUNT(*) query. Page would count all messages
    // in the chat on every request, which is wasteful for chat history.
    //
    // How the client uses this:
    //   1. First load: call without "before" param to get latest messages
    //   2. Scroll up: pass createdAt of the oldest loaded message as "before"
    //   3. Repeat until Slice.hasNext() returns false (no more history)
    //
    // Why cursor-based instead of page numbers?
    // If new messages arrive while paginating, page-based offsets shift —
    // you'd either skip messages or see duplicates. Cursor (timestamp) is
    // stable regardless of new inserts because new messages have newer timestamps.
    //
    // Uses the idx (chat_id, created_at DESC) index for fast retrieval.
    @Query("""
        SELECT m
        FROM ChatMessageEntity m
        WHERE m.chatId = :chatId
        AND m.createdAt < :before
        ORDER BY m.createdAt DESC
    """)
    fun findByChatIdBefore(
        chatId: ChatId,
        before: Instant,
        pageable: Pageable
    ): Slice<ChatMessageEntity>

    // Fetch the LATEST message for each chat in a batch.
    // Used on the chat list screen to show message previews like:
    //   "John: Hey, are you free tonight?"
    //
    // This is a single query that handles ALL chats at once instead of
    // running N separate queries (one per chat). The correlated subquery
    // finds the newest message per chat using (createdAt, id) tuple comparison:
    //
    //   (m.createdAt, m.id) = (SELECT m2.createdAt, m2.id ... ORDER BY createdAt DESC LIMIT 1)
    //
    // Why tuple comparison with both createdAt AND id?
    // If two messages have the exact same timestamp (unlikely but possible
    // under high throughput), comparing only createdAt could match multiple
    // messages. Including the id makes the match deterministic.
    //
    // LEFT JOIN FETCH m.sender eagerly loads who sent the last message
    // so the chat list can display "John: ..." without extra queries.
    @Query("""
        SELECT m
        FROM ChatMessageEntity m
        LEFT JOIN FETCH m.sender
        WHERE m.chatId IN :chatIds
        AND (m.createdAt, m.id) = (
            SELECT m2.createdAt, m2.id
            FROM ChatMessageEntity m2
            WHERE m2.chatId = m.chatId
            ORDER BY m2.createdAt DESC 
            LIMIT 1
        )
    """)
    fun findLatestMessagesByChatIds(
        chatIds: Set<ChatId>
    ): List<ChatMessageEntity>
}