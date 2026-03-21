package com.bernardooechsler.chirp.api.config

import org.springframework.stereotype.Component
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// Configures Spring MVC behavior — in this case, registering interceptors.
// WebMvcConfigurer is a Spring interface that lets you customize MVC settings
// without overriding the default auto-configuration.
@Component
class WebMvcConfig(
    // Inject the rate limit interceptor we want to register
    private val ipRateLimitInterceptor: IpRateLimitInterceptor
): WebMvcConfigurer {

    // Called by Spring during startup to let us register interceptors.
    // Without this, our IpRateLimitInterceptor would never be called.
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            // Add our interceptor to the chain
            .addInterceptor(ipRateLimitInterceptor)
            // Only apply to /api/** routes (not static files, health checks, etc.)
            // This means requests to /api/auth/login, /api/users, etc. go through the interceptor
            // But requests to /actuator/health or /static/image.png do not
            .addPathPatterns("/api/**")
    }
}