package com.bernardooechsler.chirp.infra.message_queue

import com.bernardooechsler.chirp.domain.events.ChirpEvent
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component

// Generic event publisher that bridges domain services to RabbitMQ.
// Any service can inject this to publish events without knowing
// anything about RabbitMQ internals — just pass a ChirpEvent.
@Component
class EventPublisher(
    private val rabbitTemplate: RabbitTemplate
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Accepts any ChirpEvent subtype. The event is self-describing:
    // it carries its own exchange and routing key, so the publisher
    // doesn't need any routing logic — it just forwards to the broker.
    // It doesnt need to be a generic fun, it could just be fun publish(event: ChirpEvent)
    fun <T: ChirpEvent> publish(event: T) {
        try {
            // convertAndSend does three things:
            // 1. Serializes the event to JSON via the JacksonJsonMessageConverter
            //    (including polymorphic type metadata for sealed class subtypes)
            // 2. Routes to the correct exchange (e.g., "user.events")
            // 3. Sets the routing key (e.g., "user.created") so the topic exchange
            //    can pattern-match and deliver to the right queue(s)
            rabbitTemplate.convertAndSend(
                event.exchange,
                event.eventKey,
                event
            )
            logger.info("Successfully published event: ${event.eventKey}")
        } catch(e: Exception) {
            // Swallows the exception so a broker outage doesn't break
            // the calling flow (e.g., registration still succeeds even
            // if the verification email can't be queued).
            // Trade-off: the email silently won't be sent.
            logger.error("Failed to publish ${event.eventKey} event", e)
        }
    }
}