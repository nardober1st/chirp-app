package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.api.dto.ChatMessageDto
import com.bernardooechsler.chirp.api.mappers.toChatMessageDto
import com.bernardooechsler.chirp.domain.event.ChatParticipantLeftEvent
import com.bernardooechsler.chirp.domain.event.ChatParticipantsJoinedEvent
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
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ChatService(
    private val chatRepository: ChatRepository,
    private val chatParticipantRepository: ChatParticipantRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val applicationEventPublisher: ApplicationEventPublisher
) {

    /**
     * Fetch messages older than [before] using cursor-based pagination.
     * Results come from DB in descending order (newest first) for efficient querying,
     * then get reversed so the client receives them in chronological order.
     *
     * @Cacheable tells Spring: "Before running this method, check if
     * the result is already stored in Redis. If yes, return the cached
     * result immediately and skip the method entirely."
     *
     * - value = ["messages"]
     *   → The name of the Redis cache to use. This maps to the "messages"
     *     cache configured in RedisConfig with a 30-minute TTL.
     *
     * - key = "#chatId"
     *   → The Redis key is the chatId. So each conversation gets its own
     *     cached entry. If chat "abc-123" is requested, Spring looks for
     *     key "abc-123" inside the "messages" cache.
     *
     * - condition = "#before == null && #pageSize <= 50"
     *   → Only cache the FIRST page of messages (when there's no cursor).
     *     Why? Because the first page is the "hot" data — it's what every
     *     user sees when they open a chat. Older pages (scrolling back)
     *     are accessed rarely, so caching them would waste Redis memory
     *     for little benefit. The pageSize check is a safety guard so
     *     someone requesting a huge page doesn't pollute the cache.
     *
     * - sync = true
     *   → Prevents "cache stampede." Imagine 50 users open the same group
     *     chat simultaneously and the cache is empty. Without sync, all 50
     *     would hit PostgreSQL at once with the same query. With sync = true,
     *     only ONE request queries the DB, and the other 49 wait for that
     *     result to be cached, then read from Redis. This protects the DB
     *     under high concurrency.
     */
    @Cacheable(
        value = ["messages"],
        key = "#chatId",
        condition = "#before == null && #pageSize <= 50",
        sync = true
    )
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
     * Fetch a single chat by its ID, scoped to a specific user.
     *
     * The repository's findChatById filters by both chatId AND requestUserId,
     * meaning it only returns the chat if the requesting user is actually
     * a participant. This is an authorization check baked into the query —
     * if the user isn't in the chat, null is returned instead of throwing
     * an exception, giving the caller flexibility in how to handle it.
     *
     * The toChat() mapper needs the last message to populate the chat's
     * preview (e.g., "Hey, are you free tonight?" shown under the chat name
     * in a list view), so we fetch it separately via lastMessageForChat().
     */
    fun getChatById(
        chatId: ChatId,
        requestUserId: UserId
    ): Chat? {
        return chatRepository
            .findChatById(chatId, requestUserId)
            ?.toChat(lastMessageForChat(chatId))
    }

    /**
     * Fetch all chats a user participates in, sorted by most recent activity.
     *
     * This is the "inbox" view — the list of conversations the user sees
     * when they open the app. Each chat needs its last message for the
     * preview snippet (e.g., "Alice: sounds good!" under the chat name).
     *
     * Instead of querying the last message for each chat one-by-one (N+1 problem),
     * we batch-fetch ALL last messages in a single query using the set of chat IDs,
     * then associate them by chatId into a map for O(1) lookup.
     *
     * Finally, chats are sorted by lastActivityAt (descending) so the most
     * recently active conversation appears at the top — just like WhatsApp,
     * Telegram, or any other messaging app.
     */
    fun findChatsByUser(userId: UserId): List<Chat> {
        // Step 1: Get all chat entities the user belongs to.
        val chatEntities = chatRepository.findAllByUserId(userId)

        // Step 2: Collect all chat IDs, filtering out any nulls (unsaved entities).
        val chatIds = chatEntities.mapNotNull { it.id }

        // Step 3: Batch-fetch the latest message for each chat in ONE query.
        // Then build a Map<ChatId, ChatMessageEntity> for fast lookup.
        // This avoids the N+1 problem: instead of 1 query per chat,
        // we do 1 query total regardless of how many chats the user has.
        val latestMessages = chatMessageRepository
            .findLatestMessagesByChatIds(chatIds.toSet())
            .associateBy { it.chatId }

        // Step 4: Map each entity to a domain model, attaching its last message,
        // then sort so the most recently active chat appears first.
        return chatEntities
            .map {
                it.toChat(lastMessage = latestMessages[it.id]?.toChatMessage())
            }
            .sortedByDescending { it.lastActivityAt }
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

    /**
     * Add new participants to an existing chat.
     *
     * Authorization: Only users who are ALREADY in the chat can invite others.
     * This prevents random users from adding themselves or others to chats
     * they don't belong to. We check this by scanning the chat's current
     * participant list for the requesting user's ID.
     *
     * Each new userId is looked up individually rather than using a batch query.
     * This is intentional — if ANY userId is invalid, we want to fail fast
     * with a specific ChatParticipantNotFoundException pointing to the bad ID,
     * rather than silently skipping invalid ones (which could confuse the client
     * into thinking all users were added successfully).
     *
     * After saving, we publish a ChatParticipantsJoinedEvent so other parts
     * of the system can react (e.g., sending a "X joined the chat" notification
     * or updating WebSocket connections for real-time UI updates).
     */
    @Transactional
    fun addParticipantsToChat(
        requestUserId: UserId,
        chatId: ChatId,
        userIds: Set<UserId>
    ): Chat {
        // Load the chat or fail if it doesn't exist.
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ChatNotFoundException()

        // Authorization check: is the person making this request
        // actually a member of this chat?
        val isRequestingUserInChat = chat.participants.any {
            it.userId == requestUserId
        }
        if (!isRequestingUserInChat) {
            throw ForbiddenException()
        }

        // Look up each new user one-by-one. If any ID is invalid,
        // we throw immediately rather than silently skipping.
        val users = userIds.map { userId ->
            chatParticipantRepository.findByIdOrNull(userId)
                ?: throw ChatParticipantNotFoundException(userId)
        }

        // Save the updated chat with the new participants merged in.
        // .apply {} mutates the entity in place before save() persists it.
        val lastMessage = lastMessageForChat(chatId)
        val updatedChat = chatRepository.save(
            chat.apply {
                this.participants = chat.participants + users
            }
        ).toChat(lastMessage)

        // Notify the rest of the system that new users joined.
        // Downstream listeners might send push notifications,
        // update WebSocket sessions, etc.
        applicationEventPublisher.publishEvent(
            ChatParticipantsJoinedEvent(
                chatId = chatId,
                userIds = userIds
            )
        )

        return updatedChat
    }

    /**
     * Remove a single participant from a chat (used when a user leaves).
     *
     * Special case: if the leaving user is the LAST participant, the entire
     * chat is deleted. An empty chat has no purpose — no one can see it,
     * send messages to it, or invite others into it. Deleting it avoids
     * orphaned data in the database.
     *
     * If other participants remain, we save the updated chat and publish
     * a ChatParticipantLeftEvent so the system can react (e.g., sending
     * a "X left the chat" notification to remaining members).
     *
     * Note: there's no authorization check here because this is called
     * from internal flows (e.g., a user deleting their account triggers
     * removal from all chats). The controller layer handles authorization
     * before calling this method.
     */
    @Transactional
    fun removeParticipantFromChat(
        chatId: ChatId,
        userId: UserId
    ) {
        // Load the chat or fail if it doesn't exist.
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ChatNotFoundException()

        // Find this specific participant in the chat.
        // If they're not a member, something is wrong — fail with a clear error.
        val participant = chat.participants.find { it.userId == userId }
            ?: throw ChatParticipantNotFoundException(userId)

        // Edge case: if this is the last person leaving, delete the whole chat.
        // No point keeping an empty conversation around.
        val newParticipantsSize = chat.participants.size - 1
        if (newParticipantsSize == 0) {
            chatRepository.deleteById(chatId)
            return
        }

        // Remove the participant and save the updated chat.
        chatRepository.save(
            chat.apply {
                this.participants = chat.participants - participant
            }
        )

        // Notify the system that someone left, so downstream listeners
        // can update UI, send notifications, clean up WebSocket sessions, etc.
        applicationEventPublisher.publishEvent(
            ChatParticipantLeftEvent(
                chatId = chatId,
                userId = userId
            )
        )
    }

    /**
     * Helper to fetch the most recent message in a given chat.
     *
     * Used by getChatById() and addParticipantsToChat() to attach
     * the last message preview to the Chat domain model.
     *
     * Reuses the batch query (findLatestMessagesByChatIds) with a
     * single-element set. This keeps the codebase DRY — one query
     * method serves both the "single chat" and "all chats" use cases.
     * Returns null for chats with no messages yet (freshly created).
     */
    private fun lastMessageForChat(chatId: ChatId): ChatMessage? {
        return chatMessageRepository
            .findLatestMessagesByChatIds(setOf(chatId))
            .firstOrNull()
            ?.toChatMessage()
    }
}