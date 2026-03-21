package com.bernardooechsler.chirp.infra.database.entities

import com.bernardooechsler.chirp.domain.type.ChatId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant

// Represents a conversation between two or more users.
// Each chat has a creator and a set of participants linked via a many-to-many join table.
@Entity
@Table(
    name = "chats",
    schema = "chat_service"
)
class ChatEntity(
    // Auto-generated UUID primary key — lets the database generate the ID on insert
    // rather than requiring the application to provide one.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: ChatId? = null,

    // The user who created this chat. LAZY fetch means the creator's data
    // is only loaded from the database when you actually access this field,
    // avoiding unnecessary JOINs on every chat query.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "creator_id",
        nullable = false
    )
    var creator: ChatParticipantEntity,

    // Many-to-many relationship: a chat has many participants, a user can be in many chats.
    // Instead of a @OneToMany with a back-reference, this uses a dedicated join table
    // (chat_participants_cross_ref) which is the standard relational approach for M:N relationships.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "chat_participants_cross_ref",
        schema = "chat_service",
        // "chat_id" column in the join table points back to this ChatEntity
        joinColumns = [JoinColumn(name = "chat_id")],
        // "user_id" column in the join table points to ChatParticipantEntity
        inverseJoinColumns = [JoinColumn(name = "user_id")],
        indexes = [
            // Composite index (chat_id, user_id): optimizes "give me all participants in chat X"
            // Also enforces uniqueness — a user can only appear once per chat
            Index(
                name = "idx_chat_participant_chat_id_user_id",
                columnList = "chat_id,user_id",
                unique = true
            ),
            // Reverse composite index (user_id, chat_id): optimizes "give me all chats for user X"
            // Without this, queries filtering by user_id would require a full table scan
            // because the first index starts with chat_id (index column order matters)
            Index(
                name = "idx_chat_participant_user_id_chat_id",
                columnList = "user_id,chat_id",
                unique = true
            ),
        ]
    )
    var participants: Set<ChatParticipantEntity> = emptySet(),

    // Hibernate auto-sets this to the current timestamp on insert.
    // No need to manually set it — @CreationTimestamp handles it.
    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
)