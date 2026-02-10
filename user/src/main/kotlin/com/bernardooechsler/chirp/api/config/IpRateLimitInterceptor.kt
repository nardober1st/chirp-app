package com.bernardooechsler.chirp.api.config

import com.bernardooechsler.chirp.domain.exception.RateLimitException
import com.bernardooechsler.chirp.infra.rate_limiting.IpRateLimiter
import com.bernardooechsler.chirp.infra.rate_limiting.IpResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Duration

// Interceptor that runs BEFORE controller methods execute.
// It checks if the target method has @IpRateLimit annotation and enforces the limit.
// Think of it as a gatekeeper that sits between the request and your controller.
@Component
class IpRateLimitInterceptor(
    // The rate limiter that talks to Redis
    private val ipRateLimiter: IpRateLimiter,
    // Resolves the real client IP (handles proxy headers securely)
    private val ipResolver: IpResolver,
    // Kill switch from application.yml — allows disabling rate limiting entirely
    // Useful for development, testing, or load testing scenarios
    // Example config: chirp.rate-limit.ip.apply-limit: false
    @param:Value("\${chirp.rate-limit.ip.apply-limit}")
    private val applyLimit: Boolean
): HandlerInterceptor {
    // HandlerInterceptor is a Spring interface with 3 methods:
    // - preHandle(): runs BEFORE the controller (we use this one)
    // - postHandle(): runs AFTER controller but BEFORE view rendering
    // - afterCompletion(): runs AFTER everything is done

    // This method runs before every request that matches the interceptor's path pattern.
    // Returning true = continue to controller
    // Returning false = stop here, don't call controller
    override fun preHandle(
        request: HttpServletRequest,    // The incoming HTTP request
        response: HttpServletResponse,  // The response we can write to if blocking
        handler: Any                    // The target handler (usually a controller method)
    ): Boolean {
        // First check: Is the handler a controller method AND is rate limiting enabled?
        // HandlerMethod = a controller method (as opposed to static resources, error handlers, etc.)
        // applyLimit = the kill switch from config
        if (handler is HandlerMethod && applyLimit) {

            // Check if this specific controller method has the @IpRateLimit annotation
            // Returns null if the annotation is not present
            val annotation = handler.getMethodAnnotation(IpRateLimit::class.java)

            if (annotation != null) {
                // This method IS rate limited — extract the client's real IP
                // IpResolver handles the proxy trust chain (X-Real-IP header, etc.)
                val clientIp = ipResolver.getClientIp(request)

                return try {
                    // Attempt to execute within rate limit
                    // The "action" here just returns true (meaning "allowed, continue")
                    // We don't run actual business logic here — that's the controller's job
                    ipRateLimiter.withIpRateLimit(
                        ipAddress = clientIp,
                        // Build Duration from annotation values
                        // Example: duration=1, unit=MINUTES → Duration.ofMinutes(1)
                        // toChronoUnit() converts java.util.concurrent.TimeUnit to java.time.temporal.ChronoUnit
                        resetsIn = Duration.of(
                            annotation.duration,
                            annotation.unit.toChronoUnit()
                        ),
                        // Max requests allowed within the time window
                        maxRequestsPerIp = annotation.requests,
                        // If rate limit not exceeded, this lambda runs and returns true
                        // true = "yes, continue to the controller"
                        action = { true }
                    )
                } catch (e: RateLimitException) {
                    // Rate limit exceeded — block the request
                    // Send HTTP 429 (Too Many Requests) status code
                    // The client should see this and know to slow down
                    response.sendError(429)
                    // Return false = stop processing, don't call the controller
                    false
                }
            }
        }

        // If we reach here, one of these is true:
        // - handler is not a controller method (static resource, etc.)
        // - applyLimit is false (rate limiting disabled globally)
        // - the method doesn't have @IpRateLimit annotation
        // In all these cases, allow the request to proceed normally
        return true
    }
}
/*
```
## The Flow Visualized
```
Request: POST /api/auth/login
↓
WebMvcConfig says: "Use IpRateLimitInterceptor for /api/**"
↓
preHandle() is called
↓
Is handler a HandlerMethod? → YES (it's AuthController.login())
Is applyLimit true? → YES (from config)
↓
Does login() have @IpRateLimit? → YES
↓
Get client IP via IpResolver → "203.0.113.45"
↓
Call ipRateLimiter.withIpRateLimit(...)
↓
┌───────────────────────────────────────┐
│  Redis Lua script checks counter      │
│  Current count: 6, Limit: 5           │
│  → Throws RateLimitException          │
└───────────────────────────────────────┘
↓
Catch exception → response.sendError(429)
Return false → Controller never runs
↓
Client receives: HTTP 429 Too Many Requests
*/