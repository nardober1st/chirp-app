package com.bernardooechsler.chirp.infra.rate_limiting

import com.bernardooechsler.chirp.domain.exception.RateLimitException
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component  // Marks this as a Spring-managed bean, automatically instantiated and injectable
class EmailRateLimiter(
    private val redisTemplate: StringRedisTemplate  // Spring Data Redis client for executing Redis commands
) {

    companion object {
        // Key prefixes for Redis - keeps keys organized and avoids collisions
        // Example full key: "rate_limit:email:user@example.com"
        private const val EMAIL_RATE_LIMIT_PREFIX = "rate_limit:email"
        // Example full key: "email_attempt_count:user@example.com"
        private const val EMAIL_ATTEMPT_COUNT_PREFIX = "email_attempt_count"
    }

    // Loads the Lua script file from src/main/resources/email_rate_limit.lua
    // @Value with "classpath:" tells Spring to look in the resources folder
    // lateinit because Spring injects this after construction
    @Value("classpath:email_rate_limit.lua")
    lateinit var rateLimitResource: Resource

    // Lazy initialization - only loads and parses the script on first use
    // After that, the parsed script is cached and reused for performance
    private val rateLimitScript by lazy {
        // Read the Lua file contents as a String
        // .use { } ensures the InputStream is properly closed after reading
        val script = rateLimitResource.inputStream.use {
            it.readBytes().decodeToString()
        }
        // Wrap the script for Spring Data Redis
        // The second parameter tells Redis what return type to expect (List of Longs)
        // @Suppress because Kotlin can't verify the generic type at compile time
        @Suppress("UNCHECKED_CAST")
        DefaultRedisScript(script, List::class.java as Class<List<Long>>)
    }

    // Higher-order function: takes an action (lambda) to execute if not rate-limited
    // This pattern keeps the API clean - caller just wraps their code in this function
    fun withRateLimit(
        email: String,
        action: () -> Unit  // Lambda that takes no arguments and returns nothing
    ) {
        // Normalize email to prevent bypassing limits with "User@Example.com" vs "user@example.com"
        val normalizedEmail = email.lowercase().trim()

        // Build the Redis keys for this specific email address
        val rateLimitKey = "$EMAIL_RATE_LIMIT_PREFIX:$normalizedEmail"
        val attemptCountKey = "$EMAIL_ATTEMPT_COUNT_PREFIX:$normalizedEmail"

        // Execute the Lua script atomically on Redis
        // The script receives the two keys and returns [attemptCount, ttl]
        val result = redisTemplate.execute(
            rateLimitScript,
            listOf(rateLimitKey, attemptCountKey)  // KEYS[1] and KEYS[2] in Lua
        )

        // Unpack the results from the Lua script
        val attemptCount = result[0]  // -1 means "blocked", otherwise the attempt number
        val ttl = result[1]           // Seconds until rate limit resets (0 if not blocked)

        // If Lua returned -1, the user is currently rate-limited
        if(attemptCount == -1L) {
            // Throw exception - will be caught by @ExceptionHandler and return HTTP 429
            throw RateLimitException(resetsInSeconds = ttl)
        }

        // Not rate-limited, so execute the actual business logic (e.g., send email)
        action()
    }
}