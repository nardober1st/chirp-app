package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.dto.ChatParticipantDto
import com.bernardooechsler.chirp.api.mappers.toChatParticipantDto
import com.bernardooechsler.chirp.api.util.requestUserId
import com.bernardooechsler.chirp.service.ChatParticipantService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * REST controller for looking up chat participants.
 * Serves a dual purpose through a single endpoint — either fetch
 * the current user's own profile, or search for another user.
 */
@RestController
@RequestMapping("/api/chat/participants")
class ChatParticipantController(
    private val chatParticipantService: ChatParticipantService
) {

    /**
     * GET /api/chat/participants?query=...
     *
     * Dual-mode endpoint:
     * - No query param → returns the authenticated user's own participant profile.
     *   Useful for the client to fetch "my" chat identity on app startup.
     * - With query param → searches for a participant by email or username.
     *   This is what powers the "find a user to chat with" search feature.
     *
     * Returns 404 if no matching participant is found in either mode.
     */
    @GetMapping
    fun getChatParticipantByUsernameOrEmail(
        @RequestParam(required = false) query: String?
    ): ChatParticipantDto {
        // Branch based on whether the client provided a search query:
        // null → looking up themselves, non-null → searching for someone else
        val participant = if (query == null) {
            chatParticipantService.findChatParticipantById(requestUserId)
        } else {
            chatParticipantService.findChatParticipantByEmailOrUsername(query)
        }

        // Convert to DTO or throw 404 — the Elvis operator (?:) makes this concise.
        // ResponseStatusException lets us return proper HTTP status codes without
        // needing a custom exception class for simple cases like "not found".
        return participant?.toChatParticipantDto()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}