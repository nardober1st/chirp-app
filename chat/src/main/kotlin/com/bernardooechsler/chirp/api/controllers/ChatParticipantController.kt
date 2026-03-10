package com.bernardooechsler.chirp.api.controllers

import com.bernardooechsler.chirp.api.dto.ChatParticipantDto
import com.bernardooechsler.chirp.api.dto.ConfirmProfilePictureRequest
import com.bernardooechsler.chirp.api.dto.PictureUploadResponse
import com.bernardooechsler.chirp.api.mappers.toChatParticipantDto
import com.bernardooechsler.chirp.api.mappers.toResponse
import com.bernardooechsler.chirp.api.util.requestUserId
import com.bernardooechsler.chirp.service.ChatParticipantService
import com.bernardooechsler.chirp.service.ProfilePictureService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
@RequestMapping("/api/participants")
class ChatParticipantController(
    private val chatParticipantService: ChatParticipantService,
    private val profilePictureService: ProfilePictureService
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

    /**
     * POST /api/chat/participants/profile-picture-upload?mimeType=image/png
     *
     * Step 1 of upload flow: Client requests a signed URL for uploading.
     * Returns credentials the client uses to upload directly to Supabase.
     */
    @PostMapping("/profile-picture-upload")
    fun getProfilePictureUploadUrl(
        @RequestParam mimeType: String  // e.g., "image/png", "image/jpeg"
    ): PictureUploadResponse {
        return profilePictureService.generateUploadCredentials(
            userId = requestUserId,  // From JWT — prevents uploading for other users
            mimeType = mimeType
        ).toResponse()
    }

    /**
     * POST /api/chat/participants/confirm-profile-picture
     *
     * Step 2 of upload flow: Client calls this AFTER successfully uploading
     * to Supabase. This saves the URL to the database and notifies other users.
     */
    @PostMapping("/confirm-profile-picture")
    fun confirmProfilePictureUpload(
        @Valid @RequestBody body: ConfirmProfilePictureRequest
    ) {
        profilePictureService.confirmProfilePictureUpload(
            userId = requestUserId,
            publicUrl = body.publicUrl  // The permanent URL where the image now lives
        )
    }

    /**
     * DELETE /api/chat/participants/profile-picture
     *
     * Removes the user's profile picture entirely.
     * Deletes from storage and broadcasts the change via WebSocket.
     */
    @DeleteMapping("/profile-picture")
    fun deleteProfilePicture() {
        profilePictureService.deleteProfilePicture(
            userId = requestUserId
        )
    }
}