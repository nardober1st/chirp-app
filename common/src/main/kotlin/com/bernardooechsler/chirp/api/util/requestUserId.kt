package com.bernardooechsler.chirp.api.util

import com.bernardooechsler.chirp.domain.exception.UnauthorizedException
import com.bernardooechsler.chirp.domain.type.UserId
import org.springframework.security.core.context.SecurityContextHolder

val requestUserId: UserId
    get() = SecurityContextHolder.getContext()  // Get the security context for this request
        .authentication                  // Get the Authentication object (set by JwtAuthFilter)
        ?.principal                      // Get the principal (the userId we stored)
            as? UserId                       // Cast it to UserId (returns null if can't cast)
        ?: throw UnauthorizedException() // If null, throw exception