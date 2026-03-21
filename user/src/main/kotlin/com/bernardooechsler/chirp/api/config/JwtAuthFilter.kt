package com.bernardooechsler.chirp.api.config

import com.bernardooechsler.chirp.service.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService
): OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Look for the Authorization header
        // Example: "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
        val authHeader = request.getHeader(HttpHeaders.AUTHORIZATION)

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Validate the token (check signature, expiration, etc.)
            if (jwtService.validateAccessToken(authHeader)) {
                // Token is valid — extract the userId from it
                val userId = jwtService.getUserIdFromToken(authHeader)

                // Create a Spring Security "Authentication" object
                // This is how Spring knows "who" is making the request
                val auth = UsernamePasswordAuthenticationToken(
                    userId,      // The "principal" (who the user is)
                    null,        // Credentials (not needed, token already validated)
                    emptyList()  // Authorities/roles (empty for now)
                )

                // Store it in the SecurityContext — a thread-local storage
                // that lives for the duration of this request
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        // Continue to the next filter (and eventually the controller)
        filterChain.doFilter(request, response)
    }
}