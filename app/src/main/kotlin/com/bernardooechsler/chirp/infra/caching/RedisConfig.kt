package com.bernardooechsler.chirp.infra.caching

import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import tools.jackson.databind.DefaultTyping
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration

/**
 * Redis caching configuration for the Chirp application.
 *
 * @Configuration marks this class as a source of Spring bean definitions.
 * Spring will process this class at startup and register any @Bean methods
 * as managed beans in the application context.
 *
 * @EnableCaching activates Spring's annotation-driven caching infrastructure.
 * Without this, annotations like @Cacheable, @CacheEvict, and @CachePut
 * on service methods would be completely ignored. Under the hood, Spring
 * creates AOP proxies around beans that use these annotations, intercepting
 * method calls to check the cache before executing the actual method.
 */
@Configuration
@EnableCaching
class RedisConfig {

    /**
     * Creates and configures the RedisCacheManager, which is Spring's bridge
     * between the @Cacheable/@CacheEvict annotations and the actual Redis instance.
     *
     * When a method annotated with @Cacheable("messages") is called, Spring:
     * 1. Generates a cache key (typically from the method arguments)
     * 2. Asks this CacheManager to look up that key in the "messages" cache
     * 3. If found (cache hit) → returns the cached value, skipping method execution
     * 4. If not found (cache miss) → executes the method, serializes the result
     *    to JSON using our configured serializer, and stores it in Redis
     *
     * @param connectionFactory - A LettuceConnectionFactory bean, auto-configured
     *        by Spring Boot from your application.yml redis properties (host, port, password).
     *        Lettuce is a non-blocking, thread-safe Redis client built on Netty.
     *        Spring Boot auto-creates this bean when spring-boot-starter-data-redis
     *        is on the classpath, so we just inject it here.
     */
    @Bean
    fun cacheManager(
        connectionFactory: LettuceConnectionFactory
    ): RedisCacheManager {

        /**
         * POLYMORPHIC TYPE VALIDATOR
         *
         * When we store objects in Redis as JSON, we need Jackson to also store
         * the Java/Kotlin class name alongside the data (called "type info").
         * This is because when we deserialize from Redis, Jackson needs to know
         * WHAT class to instantiate — Redis only stores raw JSON strings.
         *
         * For example, a cached list of messages would be stored as:
         * ["java.util.ArrayList", [
         *   ["com.plcoding.chirp.chat.domain.Message", {"id": "...", "text": "..."}]
         * ]]
         *
         * However, embedding class names in JSON is a known security risk.
         * A malicious payload could specify a dangerous class name and trick
         * Jackson into instantiating it (this is a "deserialization gadget attack").
         *
         * The PolymorphicTypeValidator acts as a whitelist, only allowing
         * deserialization of classes from these trusted packages:
         * - java.util.*       → ArrayList, HashMap, etc. (standard Java collections)
         * - kotlin.collections.* → Kotlin's collection wrappers
         * - com.plcoding.chirp.* → Our own domain classes (DTOs, entities, etc.)
         *
         * Anything outside these packages will be rejected during deserialization.
         */
        val polymorphicTypeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("java.util.")
            .allowIfSubType("kotlin.collections.")
            .allowIfSubType("com.plcoding.chirp.")
            .build()

        /**
         * JACKSON OBJECT MAPPER (for Redis serialization only)
         *
         * This is a separate ObjectMapper instance from the one Spring Boot
         * uses for HTTP request/response serialization. We create a dedicated
         * one because the default typing behavior needed for Redis caching
         * (embedding class names in JSON) would break REST API responses
         * if applied globally.
         *
         * - kotlinModule(): Enables proper handling of Kotlin features like
         *   data classes, default parameter values, nullable types, and
         *   single-value inline classes. Without this, Jackson wouldn't know
         *   how to construct Kotlin objects (it relies on Java reflection by default).
         *
         * - activateDefaultTyping(): Tells Jackson to embed type information
         *   (the fully qualified class name) in every serialized value.
         *   NON_FINAL means it adds type info for all non-final types
         *   (most Kotlin classes are final by default, but collections,
         *   interfaces, and open classes need this for proper deserialization).
         *   This is what allows Redis to reconstruct the exact Kotlin/Java types
         *   when reading cached values back.
         */
        val objectMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .polymorphicTypeValidator(polymorphicTypeValidator)
            .activateDefaultTyping(polymorphicTypeValidator, DefaultTyping.NON_FINAL)
            .build()

        /**
         * DEFAULT CACHE CONFIGURATION
         *
         * This defines the baseline behavior for ALL caches managed by this CacheManager.
         *
         * - entryTtl(1 hour): Every cached entry automatically expires after 1 hour.
         *   This is a "passive" expiration — Redis marks the key with a TTL and
         *   automatically deletes it when the time elapses. This prevents stale data
         *   from living in the cache indefinitely and bounds memory usage.
         *
         * - serializeValuesWith(): Configures HOW values are converted to/from
         *   the byte arrays that Redis stores. We use GenericJacksonJsonRedisSerializer
         *   backed by our custom ObjectMapper so that:
         *   a) Values are stored as human-readable JSON (easier to debug in Redis CLI)
         *   b) Type information is preserved for proper deserialization
         *   c) Kotlin data classes are handled correctly
         *
         *   The alternative would be JdkSerializationRedisSerializer (Java's native
         *   serialization), which is faster but produces unreadable binary blobs
         *   and is fragile across class version changes.
         */
        val cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofHours(1L))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJacksonJsonRedisSerializer(objectMapper)
                )
            )

        /**
         * BUILD THE CACHE MANAGER
         *
         * - cacheDefaults(cacheConfig): Applies our default config (1-hour TTL,
         *   JSON serialization) to any cache that doesn't have a specific override.
         *   So if you use @Cacheable("users"), it gets these defaults automatically.
         *
         * - withCacheConfiguration("messages", ...): Overrides the default config
         *   specifically for the "messages" cache, giving it a shorter 30-minute TTL.
         *   Chat messages change more frequently than other data, so a shorter TTL
         *   reduces the window where users might see stale messages.
         *   Any cache not explicitly configured here falls back to the defaults.
         *
         * - transactionAware(): Makes cache operations participate in Spring's
         *   @Transactional boundaries. This means cache puts/evictions are deferred
         *   until the database transaction commits successfully. If the transaction
         *   rolls back, the cache operation is discarded too. This prevents a
         *   dangerous inconsistency where the cache has new data but the database
         *   doesn't (or vice versa).
         *
         *   Example: If a service method saves a message to PostgreSQL and updates
         *   the cache, but the DB write fails and rolls back, the cache write is
         *   also rolled back — keeping cache and database in sync.
         */
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .withCacheConfiguration(
                "messages",
                cacheConfig.entryTtl(Duration.ofMinutes(30))
            )
            .transactionAware()
            .build()
    }
}