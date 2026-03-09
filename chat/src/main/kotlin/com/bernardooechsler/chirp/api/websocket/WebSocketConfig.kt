package com.bernardooechsler.chirp.api.websocket

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the ChatWebSocketHandler at a specific URL endpoint
 * so clients know where to open their WebSocket connection.
 *
 * @EnableWebSocket tells Spring to activate WebSocket support.
 * Without it, Spring ignores WebSocket handlers entirely.
 *
 * This is the raw WebSocket equivalent of what @EnableWebSocketMessageBroker
 * does for STOMP — but much simpler since we're handling routing ourselves.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    // Spring injects the handler we built — the @Component annotation
    // on ChatWebSocketHandler makes it available for injection here
    private val handler: ChatWebSocketHandler,

    // Reads the allowed origin from application.yml (e.g., "http://localhost:3000"
    // for dev, "https://chirp.app" for production). @param:Value is Kotlin's
    // way of applying @Value to the constructor parameter rather than the property.
    @param:Value("\${chirp.web-socket.allowed-origin}")
    private val allowedOrigin: String,
): WebSocketConfigurer {

    /**
     * Maps the handler to the URL path /ws/chat.
     * Clients connect with: ws://localhost:8080/ws/chat
     *
     * setAllowedOrigins controls which domains can open WebSocket
     * connections (CORS for WebSockets). Without this, browsers would
     * block cross-origin connections. Using a config property means
     * dev can allow localhost while production locks it to the real domain.
     */
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(handler, "/ws/chat")
            .setAllowedOrigins(allowedOrigin)
    }
}