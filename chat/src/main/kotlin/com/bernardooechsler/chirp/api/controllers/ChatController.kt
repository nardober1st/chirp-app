package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.dto.AddParticipantToChatDto
import com.bernardooechsler.chirp.api.dto.ChatDto
import com.bernardooechsler.chirp.api.dto.ChatMessageDto
import com.bernardooechsler.chirp.api.dto.CreateChatRequest
import com.bernardooechsler.chirp.api.mappers.toChatDto
import com.bernardooechsler.chirp.api.util.requestUserId
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.service.ChatService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * REST controller for all chat-related endpoints.
 *
 * @RestController is a convenience annotation combining @Controller + @ResponseBody.
 * Every method return value is automatically serialized to JSON (via Jackson)
 * and written to the HTTP response body — no need for explicit ResponseEntity wrappers.
 *
 * @RequestMapping("/api/chat") sets the base path for all endpoints in this controller.
 * Every method-level mapping is relative to this, so @GetMapping("/{chatId}")
 * becomes GET /api/chat/{chatId}.
 *
 * This controller is intentionally thin — it handles HTTP concerns only:
 * - Extracting path variables, query params, and request bodies
 * - Validating input (via @Valid)
 * - Mapping domain models to DTOs for the response
 * - Translating domain exceptions into HTTP status codes
 *
 * All business logic lives in ChatService. This separation keeps the controller
 * testable and prevents HTTP concerns from leaking into domain logic.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    companion object {
        /**
         * Default number of messages returned per page.
         * 20 is a common choice — enough to fill a mobile screen without
         * over-fetching. Clients can override this via the "pageSize" query param.
         */
        private const val DEFAULT_PAGE_SIZE = 20
    }

    /**
     * GET /api/chat/{chatId}/messages?before=...&pageSize=...
     *
     * Fetch messages for a chat using cursor-based pagination.
     *
     * - "before" is the cursor: an Instant timestamp. The server returns messages
     *   older than this value. On the first load, the client omits "before" to get
     *   the latest messages. To load more (infinite scroll), the client passes the
     *   timestamp of the oldest message it currently has.
     *
     * - "pageSize" controls how many messages to return. Defaults to 20.
     *   The service layer's @Cacheable only caches when pageSize <= 50,
     *   so huge page sizes won't pollute Redis.
     *
     * Both params are optional (required = false) with sensible defaults,
     * so the simplest call is just: GET /api/chat/{chatId}/messages
     */
    @GetMapping("/{chatId}/messages")
    fun getMessagesForChat(
        @PathVariable("chatId") chatId: ChatId,
        @RequestParam("before", required = false) before: Instant? = null,
        @RequestParam("pageSize", required = false) pageSize: Int = DEFAULT_PAGE_SIZE
    ): List<ChatMessageDto> {
        return chatService.getChatMessages(
            chatId = chatId,
            before = before,
            pageSize = pageSize
        )
    }

    /**
     * GET /api/chat/{chatId}
     *
     * Fetch a single chat's details (participants, last message preview, etc.).
     *
     * The service returns null if the chat doesn't exist OR if the requesting
     * user isn't a participant (authorization is baked into the query).
     * In either case, we throw a 404 — we intentionally don't distinguish
     * between "doesn't exist" and "you're not allowed" to avoid leaking
     * information about which chat IDs are valid (this is a common security
     * practice called "not found vs forbidden ambiguity").
     *
     * "requestUserId" is an extension property (from api.util) that extracts
     * the authenticated user's ID from Spring Security's SecurityContext.
     * This avoids passing Principal or Authentication objects around.
     */
    @GetMapping("/{chatId}")
    fun getChat(
        @PathVariable("chatId") chatId: ChatId,
    ): ChatDto {
        return chatService.getChatById(
            chatId = chatId,
            requestUserId = requestUserId
        )?.toChatDto() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /**
     * GET /api/chat
     *
     * Fetch all chats the authenticated user participates in.
     * This powers the "inbox" / conversation list screen.
     *
     * No pagination here — for a typical user with dozens or even
     * a few hundred chats, returning them all is fine. If the app
     * scaled to thousands of chats per user, you'd add cursor-based
     * pagination similar to getMessagesForChat().
     *
     * The service layer sorts results by lastActivityAt (newest first),
     * so the most recently active conversation appears at the top,
     * matching the UX of WhatsApp, Telegram, etc.
     */
    @GetMapping
    fun getChatsForUser(): List<ChatDto> {
        return chatService.findChatsByUser(
            userId = requestUserId,
        ).map { it.toChatDto() }
    }

    /**
     * POST /api/chat
     *
     * Create a new chat between the authenticated user and one or more others.
     *
     * @Valid triggers Jakarta Bean Validation on the request body (e.g.,
     * checking @NotEmpty on otherUserIds). If validation fails, Spring
     * automatically returns a 400 Bad Request before this method even runs.
     *
     * The request body (CreateChatRequest) contains a list of user IDs.
     * We convert it to a Set to eliminate duplicates — if the client
     * accidentally sends the same userId twice, we don't want to process
     * it twice or throw a confusing error.
     *
     * The creator is always the authenticated user (requestUserId),
     * extracted from the JWT token. This prevents a client from creating
     * chats on behalf of other users.
     */
    @PostMapping
    fun createChat(
        @Valid @RequestBody body: CreateChatRequest
    ): ChatDto {
        return chatService.createChat(
            creatorId = requestUserId,
            otherUserIds = body.otherUserIds.toSet()
        ).toChatDto()
    }

    /**
     * POST /api/chat/{chatId}/add
     *
     * Add new participants to an existing chat.
     *
     * Only current chat members can add others — the service layer
     * checks that requestUserId is already a participant and throws
     * ForbiddenException if not.
     *
     * Like createChat, we convert the list to a Set to deduplicate.
     * The service will throw ChatParticipantNotFoundException if any
     * of the provided user IDs don't exist, ensuring the client gets
     * clear feedback about invalid inputs.
     */
    @PostMapping("/{chatId}/add")
    fun addChatParticipants(
        @PathVariable chatId: ChatId,
        @Valid @RequestBody body: AddParticipantToChatDto
    ): ChatDto {
        return chatService.addParticipantsToChat(
            requestUserId = requestUserId,
            chatId = chatId,
            userIds = body.userIds.toSet()
        ).toChatDto()
    }

    /**
     * DELETE /api/chat/{chatId}/leave
     *
     * Remove the authenticated user from a chat (i.e., "leave" the chat).
     *
     * Uses DELETE because the user is removing their own participation
     * resource. The userId isn't in the URL — it's always the authenticated
     * user (requestUserId). This prevents users from kicking others out
     * via this endpoint.
     *
     * Returns void (no response body). Spring automatically returns
     * 200 OK for void methods. If the chat doesn't exist or the user
     * isn't a participant, the service throws an exception that gets
     * mapped to the appropriate HTTP error by the global exception handler.
     *
     * If this user is the last participant, the service deletes the
     * entire chat to avoid orphaned data.
     */
    @DeleteMapping("/{chatId}/leave")
    fun leaveChat(
        @PathVariable chatId: ChatId
    ) {
        chatService.removeParticipantFromChat(
            chatId = chatId,
            userId = requestUserId
        )
    }
}