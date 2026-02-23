package com.bernardooechsler.chirp.infra.message_queue

import com.bernardooechsler.chirp.domain.events.ChirpEvent
import com.bernardooechsler.chirp.domain.events.chat.ChatEventConstants
import com.bernardooechsler.chirp.domain.events.user.UserEventConstants
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DefaultTyping
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import tools.jackson.module.kotlin.kotlinModule

// Centralizes all RabbitMQ infrastructure: serialization, template, and topology (exchanges/queues).
@Configuration
class RabbitMqConfig {

    // Configures how events are serialized/deserialized to/from JSON when sent through RabbitMQ.
    // Since UserEvent is a sealed class hierarchy, we need polymorphic type handling so
    // the consumer can deserialize back to the correct subtype (Created, Verified, etc.).
    @Bean
    fun messageConverter(): JacksonJsonMessageConverter {
        // Security: restricts which types Jackson is allowed to deserialize.
        // Without this, an attacker could craft a message with a malicious class name
        // and Jackson would instantiate it (deserialization attack).
        // Only ChirpEvent subtypes and standard collections are permitted.
        val polymorphicTypeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(ChirpEvent::class.java)
            .allowIfSubType("java.util.")
            .allowIfSubType("kotlin.collections.")
            .build()

        // Custom ObjectMapper with Kotlin support and polymorphic typing enabled.
        // activateDefaultTyping embeds type info (e.g., the fully qualified class name)
        // into the JSON, so the consumer knows which UserEvent subclass to deserialize into.
        // NON_FINAL means type info is added for non-final classes (sealed class subtypes).
        val objectMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .polymorphicTypeValidator(polymorphicTypeValidator)
            .activateDefaultTyping(polymorphicTypeValidator, DefaultTyping.NON_FINAL)
            .build()

        // TYPE_ID precedence means the converter uses the type identifier embedded
        // in the message headers/payload to determine the target class,
        // rather than relying on the method parameter type of the listener.
        return JacksonJsonMessageConverter(objectMapper).apply {
            typePrecedence = JacksonJavaTypeMapper.TypePrecedence.TYPE_ID
        }
    }

    // The RabbitTemplate is the main component for publishing messages.
    // Wires in the connection factory (configured via application.yml)
    // and our custom JSON converter so all outgoing messages are
    // automatically serialized with polymorphic type info.
    @Bean
    fun rabbitTemplate(
        connectionFactory: ConnectionFactory,
        messageConverter: JacksonJsonMessageConverter,
    ): RabbitTemplate {
        return RabbitTemplate(connectionFactory).apply {
            this.messageConverter = messageConverter
        }
    }

    // Topic exchange for user events — routes messages by pattern-matching the routing key.
    // durable = true: survives broker restarts
    // autoDelete = false: persists even when no queues are bound
    @Bean
    fun userExchange() = TopicExchange(
        UserEventConstants.USER_EXCHANGE,
        true,
        false
    )

    @Bean
    fun chatExchange() = TopicExchange(
        ChatEventConstants.CHAT_EXCHANGE,
        true,
        false
    )

    @Bean
    fun chatUserEventsQueue() = Queue(
        MessageQueues.CHAT_USER_EVENTS,
        true
    )

    // Queue dedicated to the notification service for consuming user events.
    // Each service gets its own queue, so multiple services can independently
    // process the same events from the exchange.
    // durable = true: queue and messages survive broker restarts
    @Bean
    fun notificationUserEventsQueue() = Queue(
        MessageQueues.NOTIFICATION_USER_EVENTS,
        true
    )

    @Bean
    fun notificationUserEventsBinding(
        notificationUserEventsQueue: Queue,
        userExchange: TopicExchange,
    ): Binding {
        return BindingBuilder
            .bind(notificationUserEventsQueue)
            .to(userExchange)
            .with("user.*")
    }

    @Bean
    fun chatUserEventsBinding(
        chatUserEventsQueue: Queue,
        userExchange: TopicExchange,
    ): Binding {
        return BindingBuilder
            .bind(chatUserEventsQueue)
            .to(userExchange)
            .with("user.*")
    }
}