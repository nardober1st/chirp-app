package com.bernardooechsler.chirp.infra.rate_limiting

import com.bernardooechsler.chirp.domain.exception.RateLimitException
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

// Rate limiter that throttles requests per IP address using a Redis-backed sliding counter.
@Component
class IpRateLimiter(
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        // Redis key prefix used to namespace all IP rate-limit counters
        private const val IP_RATE_LIMIT_PREFIX = "rate_limit:ip"
    }

    // Injects the Lua script file from the classpath at startup
    @Value("classpath:ip_rate_limit.lua")
    lateinit var rateLimitResource: Resource

    // Lazily parses the Lua script into a RedisScript on first use.
    // The script atomically increments the counter and sets the TTL in a single Redis roundtrip.
    private val rateLimitScript by lazy {
        val script = rateLimitResource.inputStream.use {
            it.readBytes().decodeToString()
        }
        @Suppress("UNCHECKED_CAST")
        DefaultRedisScript(script, List::class.java as Class<List<Long>>)
    }

    /**
     * Wraps an [action] with IP-based rate limiting.
     *
     * Executes the Lua script in Redis which atomically increments a per-IP counter
     * and returns [currentCount, ttl]. If the counter is within the allowed limit,
     * the action is executed; otherwise a [RateLimitException] is thrown with the
     * remaining TTL so the caller knows when to retry.
     */
    fun <T> withIpRateLimit(
        ipAddress: String,
        resetsIn: Duration,
        maxRequestsPerIp: Int,
        action: () -> T
    ): T {
        // Build the Redis key, e.g. "rate_limit:ip:192.168.1.1"
        val key = "$IP_RATE_LIMIT_PREFIX:$ipAddress"

        // Execute the Lua script atomically — returns a list of [currentCount, ttl]
        val result = redisTemplate.execute(
            rateLimitScript,
            listOf(key),
            maxRequestsPerIp.toString(),
            resetsIn.seconds.toString()
        )

        val currentCount = result[0]

        // Allow the request if the counter hasn't exceeded the limit
        return if(currentCount <= maxRequestsPerIp) {
            action()
        } else {
            // Rate limit exceeded — throw with the TTL so the client knows when the window resets
            val ttl = result[1]
            throw RateLimitException(resetsInSeconds = ttl)
        }
    }
}