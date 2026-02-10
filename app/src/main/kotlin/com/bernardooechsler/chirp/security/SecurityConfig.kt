package com.bernardooechsler.chirp.security

import com.bernardooechsler.chirp.api.config.JwtAuthFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

// Configures Spring Security for JWT-based stateless authentication
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(httpSecurity: HttpSecurity, jwtAuthFilter: JwtAuthFilter): SecurityFilterChain {
        return httpSecurity
            // Disable CSRF — not needed for stateless APIs (no cookies/sessions)
            .csrf { it.disable() }
            // Don't create HTTP sessions — we use JWT tokens instead
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Define which endpoints require authentication
            .authorizeHttpRequests { auth ->
                auth
                    // Public auth endpoints (login, register, forgot-password, etc.)
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // Exception: change-password requires being logged in
                    .requestMatchers("/api/auth/change-password")
                    .authenticated()
                    // Allow Spring's internal error/forward dispatches
                    .dispatcherTypeMatchers(
                        DispatcherType.ERROR,
                        DispatcherType.FORWARD
                    )
                    .permitAll()
                    // Everything else requires authentication
                    .anyRequest()
                    .authenticated()
            }
            // Insert our JWT filter before Spring's default username/password filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Return 401 Unauthorized (not redirect to login page) when auth fails
            .exceptionHandling { configurer ->
                configurer
                    .authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .build()
    }
}