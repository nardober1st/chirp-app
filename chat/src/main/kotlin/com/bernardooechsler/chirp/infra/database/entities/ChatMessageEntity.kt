package com.bernardooechsler.chirp.infra.database.entities

import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.ChatMessageId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant

// Represents a single message within a chat.
// Uses a dual-mapping pattern for the chat_id column: a plain @Column for writes
// and a read-only @ManyToOne for navigating to the parent ChatEntity.
@Entity
@Table(
    name = "chat_messages",
    schema = "chat_service",
    indexes = [
        // Composite index on (chat_id, created_at DESC): this is THE critical index
        // for chat apps. Every time a user opens a chat, we query
        // "get me the most recent messages for this chat" — this index makes
        // that query fast by pre-sorting messages per chat by timestamp.
        Index(
            name = "idx_chat_message_chat_id_created_at",
            columnList = "chat_id,created_at DESC"
        )
    ]
)
class ChatMessageEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: ChatMessageId? = null,

    // The actual text content of the message
    @Column(nullable = false)
    var content: String,

    // RAW FOREIGN KEY — this is the "write" side of the dual mapping.
    // When creating a message, you set this field directly with the chat's UUID
    // instead of loading the entire ChatEntity. Uses @Column (not @JoinColumn)
    // because @JoinColumn would conflict with the @ManyToOne below — both would
    // claim ownership of the chat_id column, causing a DuplicateMappingException.
    @Column(
        name = "chat_id",
        nullable = false,
        updatable = false
    )
    var chatId: ChatId,

    // RELATIONSHIP — this is the "read" side of the dual mapping.
    // Lets you navigate from a message to its parent chat (message.chat).
    // insertable = false, updatable = false tells Hibernate: "this relationship
    // reads from the chat_id column but never writes to it." The chatId field
    // above handles all writes. This avoids the DuplicateMappingException.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "chat_id",
        nullable = false,
        insertable = false,
        updatable = false
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    var chat: ChatEntity? = null,

    // The user who sent this message. Same read-only pattern — the sender_id
    // is set elsewhere (likely a separate @Column field to be added),
    // and this relationship is just for navigation/querying.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "sender_id",
        nullable = false
    )
    var sender: ChatParticipantEntity,

    @CreationTimestamp
    var createdAt: Instant = Instant.now()
)