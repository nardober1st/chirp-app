package com.bernardooechsler.chirp.api.websocket

import com.bernardooechsler.chirp.api.dto.ws.ChatParticipantsChangedDto
import com.bernardooechsler.chirp.api.dto.ws.DeleteMessageDto
import com.bernardooechsler.chirp.api.dto.ws.ErrorDto
import com.bernardooechsler.chirp.api.dto.ws.IncomingWebSocketMessage
import com.bernardooechsler.chirp.api.dto.ws.IncomingWebSocketMessageType
import com.bernardooechsler.chirp.api.dto.ws.OutgoingWebSocketMessage
import com.bernardooechsler.chirp.api.dto.ws.OutgoingWebSocketMessageType
import com.bernardooechsler.chirp.api.dto.ws.ProfilePictureUpdateDto
import com.bernardooechsler.chirp.api.dto.ws.SendMessageDto
import com.bernardooechsler.chirp.api.mappers.toChatMessageDto
import com.bernardooechsler.chirp.domain.event.ChatCreatedEvent
import com.bernardooechsler.chirp.domain.event.ChatParticipantLeftEvent
import com.bernardooechsler.chirp.domain.event.ChatParticipantsJoinedEvent
import com.bernardooechsler.chirp.domain.event.MessageDeletedEvent
import com.bernardooechsler.chirp.domain.event.ProfilePictureUpdatedEvent
import com.bernardooechsler.chirp.domain.type.ChatId
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.service.ChatMessageService
import com.bernardooechsler.chirp.service.ChatService
import com.bernardooechsler.chirp.service.JwtService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Raw WebSocket handler for real-time chat messaging.
 *
 * Instead of STOMP (which adds pub/sub protocol overhead), this uses plain
 * WebSocket with manual session routing. Four in-memory maps act as a
 * routing table to track who is connected and which chats they belong to,
 * so messages can be broadcast to the right sessions.
 */
@Component
class ChatWebSocketHandler(
    private val chatMessageService: ChatMessageService,
    private val objectMapper: ObjectMapper,
    private val chatService: ChatService,
    private val jwtService: JwtService
) : TextWebSocketHandler() {

    companion object {
        private const val PING_INTERVAL_MS = 30_000L
        private const val PONG_TIMEOUT_MS = 60_000L
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Protects connect/disconnect operations from race conditions.
     * Write lock = connecting or disconnecting (mutating the maps).
     * Read lock = broadcasting messages (reading the maps concurrently).
     * Multiple broadcasts can happen at once, but they pause during a connect/disconnect.
     */
    private val connectionLock = ReentrantReadWriteLock()

    // --- The four routing maps ---

    // sessionId → UserSession: "Who owns this WebSocket session?"
    private val sessions = ConcurrentHashMap<String, UserSession>()

    // userId → Set<sessionId>: "What sessions does this user have open?" (supports multiple tabs/devices)
    private val userToSessions = ConcurrentHashMap<UserId, MutableSet<String>>()

    // userId → Set<chatId>: "What chats is this user part of?" (cached on first connect to avoid repeated DB queries)
    private val userChatIds = ConcurrentHashMap<UserId, MutableSet<ChatId>>()

    // chatId → Set<sessionId>: "Which sessions should receive messages for this chat?" (the broadcast map)
    private val chatToSessions = ConcurrentHashMap<ChatId, MutableSet<String>>()

    /**
     * Called when a new WebSocket connection is opened.
     *
     * Flow:
     * 1. Extract and validate JWT from the handshake Authorization header
     * 2. Register the session in all four routing maps
     * 3. Load the user's chats (only on first connection — cached after that)
     * 4. Link this session to each of the user's chats for broadcast routing
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val authHeader = session
            .handshakeHeaders
            .getFirst(HttpHeaders.AUTHORIZATION)
            ?: run {
                logger.warn("Session ${session.id} was closed due to missing Authorization header")
                session.close(CloseStatus.SERVER_ERROR.withReason("Authentication failed"))
                return
            }

        val userId = jwtService.getUserIdFromToken(authHeader)

        val userSession = UserSession(
            userId = userId,
            session = session
        )

        connectionLock.write {
            sessions[session.id] = userSession

            userToSessions.compute(userId) { _, existingSessions ->
                (existingSessions ?: mutableSetOf()).apply {
                    add(session.id)
                }
            }

            val chatIds = userChatIds.computeIfAbsent(userId) {
                val chatIds = chatService.findChatsByUser(userId).map { it.id }
                ConcurrentHashMap.newKeySet<ChatId>().apply {
                    addAll(chatIds)
                }
            }

            chatIds.forEach { chatId ->
                chatToSessions.compute(chatId) { _, sessions ->
                    (sessions ?: mutableSetOf()).apply {
                        add(session.id)
                    }
                }
            }
        }

        logger.info("Websocket connection established for user $userId")
    }

    /**
     * Called when a WebSocket session closes, whether intentionally (user closes tab,
     * client calls close()) or forcefully (ping timeout, transport error).
     *
     * This is the SINGLE cleanup point for all four routing maps.
     * Every path that closes a session — user closing the tab, ping timeout,
     * transport error — ends up here, so cleanup logic isn't duplicated.
     *
     * Mirrors afterConnectionEstablished in reverse:
     *   connect: add to sessions → add to userToSessions → add to chatToSessions
     *   disconnect: remove from sessions → remove from userToSessions → remove from chatToSessions
     */
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connectionLock.write {
            // Remove from the master registry and get the UserSession back.
            // .let {} only runs if remove() returned non-null (session existed).
            // If it was already removed (double-close edge case), this is a no-op.
            sessions.remove(session.id)?.let { userSession ->
                val userId = userSession.userId

                // Remove this session from the user's session set.
                // Same .takeIf pattern as onLeftChat — if this was the user's
                // last session, delete the key entirely to avoid empty sets.
                userToSessions.compute(userId) { _, sessions ->
                    sessions
                        ?.apply { remove(session.id) }
                        ?.takeIf { it.isNotEmpty() }
                }

                // Remove this session from every chat's broadcast list.
                // Note: we DON'T remove from userChatIds here — the user's
                // chat memberships are still valid, they're just offline.
                // If they reconnect, computeIfAbsent in afterConnectionEstablished
                // will find the cached set and skip the DB query.
                userChatIds[userId]?.forEach { chatId ->
                    chatToSessions.compute(chatId) { _, sessions ->
                        sessions
                            ?.apply { remove(session.id) }
                            ?.takeIf { it.isNotEmpty() }
                    }
                }

                logger.info("Websocket session closed for user $userId")
            }
        }
    }

    /**
     * Called when a low-level transport error occurs on the WebSocket connection.
     * Examples: network interruption, protocol violation, buffer overflow.
     *
     * We log the error for debugging, then force-close the session.
     * The close() call triggers afterConnectionClosed() above, which
     * handles all the map cleanup — so no cleanup logic needed here.
     */
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("Transport error for session ${session.id}", exception)
        session.close(CloseStatus.SERVER_ERROR.withReason("Transport error"))
    }


    /**
     * Called every time a connected client sends a text message over WebSocket.
     *
     * The message format is always an IncomingWebSocketMessage wrapper with:
     *   - type: tells us WHAT kind of action this is (e.g., NEW_MESSAGE)
     *   - payload: the actual data as a JSON string (e.g., SendMessageDto)
     *
     * This is basically a mini router — the `when` block dispatches to the
     * right handler based on the message type, similar to how a REST controller
     * routes based on HTTP method and path.
     */
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        logger.debug("Received message ${message.payload}")

        // Look up who sent this — if the session isn't registered, bail out.
        // Uses a read lock since we're only reading from the sessions map.
        val userSession = connectionLock.read {
            sessions[session.id] ?: return
        }

        try {
            // First parse: unwrap the outer envelope to get the type + raw payload
            val webSocketMessage = objectMapper.readValue(
                message.payload,
                IncomingWebSocketMessage::class.java
            )
            // Route to the correct handler based on message type
            when (webSocketMessage.type) {
                IncomingWebSocketMessageType.NEW_MESSAGE -> {
                    // Second parse: deserialize the payload string into the specific DTO
                    val dto = objectMapper.readValue(
                        webSocketMessage.payload,
                        SendMessageDto::class.java
                    )
                    handleSendMessage(
                        dto = dto,
                        senderId = userSession.userId
                    )
                }
            }
        } catch (e: JacksonException) {
            // If the JSON is malformed or UUIDs are invalid, don't crash —
            // just tell the sender what went wrong
            logger.warn("Could not parse message ${message.payload}", e)
            sendError(
                session = userSession.session,
                error = ErrorDto(
                    code = "INVALID_JSON",
                    message = "Incoming JSON or UUID is invalid"
                )
            )
        }
    }

    /**
     * Listens for message deletion events from the service layer.
     *
     * @TransactionalEventListener with AFTER_COMMIT means this only fires
     * AFTER the database transaction that deleted the message has fully committed.
     * This prevents broadcasting a "message deleted" notification to clients
     * when the DB delete might still roll back due to an error.
     *
     * No routing map updates needed here — we're just telling clients
     * to remove a message from their UI.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDeleteMessage(event: MessageDeletedEvent) {
        broadcastToChat(
            chatId = event.chatId,
            message = OutgoingWebSocketMessage(
                type = OutgoingWebSocketMessageType.MESSAGE_DELETED,
                payload = objectMapper.writeValueAsString(
                    DeleteMessageDto(
                        chatId = event.chatId,
                        messageId = event.messageId
                    )
                )
            )
        )
    }

    private fun updateChatForUsers(
        chatId: ChatId,
        userIds: List<UserId>
    ) {
        connectionLock.write {
            userIds.forEach { userId ->
                userChatIds.compute(userId) { _, chatIds ->
                    (chatIds ?: mutableSetOf()).apply {
                        add(chatId)
                    }
                }

                userToSessions[userId]?.forEach { sessionId ->
                    chatToSessions.compute(chatId) { _, sessions ->
                        (sessions ?: mutableSetOf()).apply { add(sessionId) }
                    }
                }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChatCreated(event: ChatCreatedEvent) {
        updateChatForUsers(event.chatId, userIds = event.participantIds)
    }

    /**
     * Listens for new participants joining a chat.
     *
     * This does TWO things:
     * 1. Updates the routing maps so the new participants receive future messages
     * 2. Broadcasts to everyone in the chat that participants changed
     *
     * The map updates happen inside a write lock because we're modifying
     * multiple maps and no broadcast should read a half-updated state.
     * The broadcast happens OUTSIDE the write lock — no need to hold it
     * while sending messages over the network.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onJoinChat(event: ChatParticipantsJoinedEvent) {
        updateChatForUsers(event.chatId, userIds = event.userIds.toList())

        // Notify everyone in the chat (including the new participants,
        // who were just added to the routing maps above) that the
        // participant list changed — clients can refresh their member list
        broadcastToChat(
            chatId = event.chatId,
            message = OutgoingWebSocketMessage(
                type = OutgoingWebSocketMessageType.CHAT_PARTICIPANTS_CHANGED,
                payload = objectMapper.writeValueAsString(
                    ChatParticipantsChangedDto(
                        chatId = event.chatId
                    )
                )
            )
        )
    }

    /**
     * Called automatically by Spring when a client responds to our ping with a pong.
     *
     * WebSocket has built-in ping/pong frames (separate from text messages).
     * When we send a PingMessage, the client's browser/WebSocket library
     * automatically replies with a PongMessage — no client-side code needed.
     *
     * We record the timestamp of the last pong so the scheduled pingClients()
     * can check: "has this session responded recently, or is it dead?"
     */
    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        // Write lock because we're modifying the sessions map
        connectionLock.write {
            sessions.compute(session.id) { _, userSession ->
                // .copy() creates a new UserSession with just lastPongTimestamp updated.
                // If userSession is null (session already removed), returns null (no-op).
                userSession?.copy(
                    lastPongTimestamp = System.currentTimeMillis()
                )
            }
        }
        logger.debug("Received pong from ${session.id}")
    }

    /**
     * Runs on a fixed schedule (every PING_INTERVAL_MS milliseconds).
     * Acts as a health check for all connected sessions.
     *
     * Why this is needed: WebSocket connections can die silently.
     * If a user's WiFi drops or their laptop crashes, the server never
     * gets a proper close frame — the session just sits there looking
     * "open" but is actually dead. Without this, dead sessions would
     * pile up in the routing maps and messages would be "sent" to
     * connections that no longer exist.
     *
     * Flow:
     * 1. Take a snapshot of all sessions
     * 2. For each session, check if the last pong was too long ago
     * 3. If timed out → mark for closure. If alive → send a new ping
     * 4. Close all timed-out sessions after the loop
     */
    @Scheduled(fixedDelay = PING_INTERVAL_MS)
    fun pingClients() {
        val currentTime = System.currentTimeMillis()

        // Collect sessions to close AFTER the loop — we don't want to
        // modify the maps while iterating over them
        val sessionsToClose = mutableListOf<String>()

        // Snapshot of all sessions so we don't hold the lock during
        // network I/O (sending pings). toMap() creates an independent copy.
        val sessionsSnapshot = connectionLock.read { sessions.toMap() }

        sessionsSnapshot.forEach { (sessionId, userSession) ->
            try {
                if (userSession.session.isOpen) {
                    val lastPong = userSession.lastPongTimestamp

                    // Check if the session has gone silent for too long.
                    // If the gap between now and the last pong exceeds the timeout,
                    // the connection is considered dead.
                    if (currentTime - lastPong > PONG_TIMEOUT_MS) {
                        logger.warn("Session $sessionId has timed out, closing connection.")
                        sessionsToClose.add(sessionId)
                        return@forEach // Skip sending a ping to a dead session
                    }

                    // Session is healthy — send a ping and wait for the pong.
                    // PingMessage is a WebSocket protocol-level frame, not a text message.
                    // The client's WebSocket implementation responds automatically.
                    userSession.session.sendMessage(PingMessage())
                    logger.debug("Sent ping to {}", userSession.userId)
                }
            } catch (e: Exception) {
                // If we can't even send a ping, the connection is broken.
                // Mark it for closure instead of crashing the entire loop.
                logger.error("Could not ping session $sessionId", e)
                sessionsToClose.add(sessionId)
            }
        }

        // Second pass: close all dead/unresponsive sessions.
        // Done separately from the check loop to keep the logic clean
        // and avoid modifying state while iterating.
        sessionsToClose.forEach { sessionId ->
            connectionLock.read {
                sessions[sessionId]?.session?.let { session ->
                    try {
                        // GOING_AWAY status tells the client this wasn't an error,
                        // the server is intentionally closing due to inactivity.
                        // This triggers afterConnectionClosed() which cleans up
                        // all four routing maps.
                        session.close(CloseStatus.GOING_AWAY.withReason("Ping timeout"))
                    } catch (e: Exception) {
                        logger.error("Couldn't close sessions for session ${session.id}")
                    }
                }
            }
        }
    }

    /**
     * Listens for a participant leaving a chat.
     *
     * Mirrors onJoinChat but in reverse — removes the user from
     * the routing maps so they stop receiving messages for this chat.
     *
     * The .takeIf { it.isNotEmpty() } pattern is a cleanup trick:
     * if removing the entry leaves the set empty, return null instead,
     * which tells ConcurrentHashMap.compute() to DELETE the key entirely.
     * This prevents the maps from accumulating empty sets over time
     * (a memory leak).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onLeftChat(event: ChatParticipantLeftEvent) {
        connectionLock.write {
            // Remove this chat from the user's cached chat set.
            // If they have no chats left, the key is removed entirely.
            userChatIds.compute(event.userId) { _, chatIds ->
                chatIds
                    ?.apply { remove(event.chatId) }  // remove the chat from the set
                    ?.takeIf { it.isNotEmpty() }       // if set is now empty, return null to delete the key
            }

            // Remove all of this user's sessions from the chat's broadcast list
            // so they stop receiving messages for this chat
            userToSessions[event.userId]?.forEach { sessionId ->
                chatToSessions.compute(event.chatId) { _, sessions ->
                    sessions
                        ?.apply { remove(sessionId) }  // remove this session from the chat
                        ?.takeIf { it.isNotEmpty() }   // clean up empty sets
                }
            }
        }

        // Notify remaining participants that someone left.
        // The user who left will NOT receive this because their sessions
        // were just removed from chatToSessions above.
        broadcastToChat(
            chatId = event.chatId,
            message = OutgoingWebSocketMessage(
                type = OutgoingWebSocketMessageType.CHAT_PARTICIPANTS_CHANGED,
                payload = objectMapper.writeValueAsString(
                    ChatParticipantsChangedDto(
                        chatId = event.chatId
                    )
                )
            )
        )
    }

    // Listens for ProfilePictureUpdatedEvent, but only runs AFTER the DB transaction commits
// This prevents broadcasting a change that might get rolled back
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfilePictureUpdated(event: ProfilePictureUpdatedEvent) {
        // Step 1: Find all chats this user is part of
        // (so we know which other users need to see the update)
        val userChats = connectionLock.read {
            userChatIds[event.userId]?.toList() ?: emptyList()
        }

        // Step 2: Build the DTO payload
        val dto = ProfilePictureUpdateDto(
            userId = event.userId,
            newUrl = event.newUrl,  // null if picture was deleted
        )

        // Step 3: Collect all WebSocket session IDs across all the user's chats
        // These are the sessions that need to receive the update
        val sessionIds = mutableSetOf<String>()  // Set prevents duplicates
        userChats.forEach { chatId ->
            connectionLock.read {
                chatToSessions[chatId]?.let { sessions ->
                    sessionIds.addAll(sessions)
                }
            }
        }

        // Step 4: Wrap in the standard outgoing message format
        val webSocketMessage = OutgoingWebSocketMessage(
            type = OutgoingWebSocketMessageType.PROFILE_PICTURE_UPDATED,
            payload = objectMapper.writeValueAsString(dto)
        )
        val messageJson = objectMapper.writeValueAsString(webSocketMessage)

        // Step 5: Send to each session
        sessionIds.forEach { sessionId ->
            val userSession = connectionLock.read {
                sessions[sessionId]
            } ?: return@forEach  // Session gone, skip it

            try {
                if (userSession.session.isOpen) {
                    userSession.session.sendMessage(TextMessage(messageJson))
                }
            } catch (e: Exception) {
                // Don't let one failed session break the others
                logger.error("Could not send profile picture update to session $sessionId", e)
            }
        }
    }

    /**
     * Sends an error message back to a single session.
     * Wraps the ErrorDto inside an OutgoingWebSocketMessage with type ERROR,
     * so the client can distinguish errors from normal messages.
     * The try-catch around sendMessage handles the case where the session
     * closed between when we decided to send and when we actually send.
     */
    private fun sendError(
        session: WebSocketSession,
        error: ErrorDto
    ) {
        val webSocketMessage = objectMapper.writeValueAsString(
            OutgoingWebSocketMessage(
                type = OutgoingWebSocketMessageType.ERROR,
                payload = objectMapper.writeValueAsString(error)
            )
        )

        try {
            session.sendMessage(TextMessage(webSocketMessage))
        } catch (e: Exception) {
            logger.warn("Couldn't send error message", e)
        }
    }

    /**
     * Broadcasts a message to ALL sessions subscribed to a specific chat.
     *
     * Flow:
     * 1. Look up which sessions are in this chat (read lock — safe for concurrent broadcasts)
     * 2. Copy the list with toList() so we're not iterating over the live set
     *    (which could change if someone connects/disconnects mid-broadcast)
     * 3. Send the message to each session via sendToUser
     */
    private fun broadcastToChat(
        chatId: ChatId,
        message: OutgoingWebSocketMessage
    ) {
        val chatSessions = connectionLock.read {
            chatToSessions[chatId]?.toList() ?: emptyList()
        }

        chatSessions.forEach { sessionId ->
            val userSession = connectionLock.read {
                sessions[sessionId]
            } ?: return@forEach // Session might have disconnected — skip it

            sendToUser(
                userId = userSession.userId,
                message = message
            )
        }
    }

    /**
     * Handles a NEW_MESSAGE action from a client.
     *
     * Security check: verifies the sender actually belongs to the target chat
     * using the cached userChatIds map (no DB hit). If they don't belong,
     * silently ignores the message — no error sent to avoid leaking info
     * about which chat IDs exist.
     *
     * After persisting the message via the service layer, broadcasts it
     * to everyone in the chat (including the sender, so their UI confirms delivery).
     */
    private fun handleSendMessage(
        dto: SendMessageDto,
        senderId: UserId
    ) {
        // Authorization: check the in-memory cache to see if this user is in the chat
        val userChatIds = connectionLock.read { this@ChatWebSocketHandler.userChatIds[senderId] } ?: return

        if (dto.chatId !in userChatIds) {
            return
        }

        // Persist to DB via the service layer
        val savedMessage = chatMessageService.sendMessage(
            chatId = dto.chatId,
            senderId = senderId,
            content = dto.content,
            messageId = dto.messageId
        )

        // Broadcast to all connected participants in this chat
        broadcastToChat(
            chatId = dto.chatId,
            message = OutgoingWebSocketMessage(
                type = OutgoingWebSocketMessageType.NEW_MESSAGE,
                payload = objectMapper.writeValueAsString(
                    savedMessage.toChatMessageDto()
                )
            )
        )
    }

    /**
     * Sends a message to ALL open sessions for a specific user.
     *
     * A single user might have multiple sessions (phone + laptop + tablet),
     * so we iterate through all of them. Checks isOpen before sending to
     * avoid exceptions on stale sessions that haven't been cleaned up yet.
     */
    private fun sendToUser(userId: UserId, message: OutgoingWebSocketMessage) {
        val userSessions = connectionLock.read {
            userToSessions[userId] ?: emptySet()
        }
        userSessions.forEach { sessionId ->
            val userSession = connectionLock.read {
                sessions[sessionId] ?: return@forEach
            }
            if (userSession.session.isOpen) {
                try {
                    val messageJson = objectMapper.writeValueAsString(message)
                    userSession.session.sendMessage(TextMessage(messageJson))
                    logger.debug("Sent message to user {}: {}", userId, messageJson)
                } catch (e: Exception) {
                    logger.error("Error while sending message to $userId", e)
                }
            }
        }
    }

    /**
     * Pairs a WebSocket session with its authenticated user.
     * Stored in the sessions map for quick lookups during message handling.
     */
    private data class UserSession(
        val userId: UserId,
        val session: WebSocketSession,
        val lastPongTimestamp: Long = System.currentTimeMillis()
    )
}