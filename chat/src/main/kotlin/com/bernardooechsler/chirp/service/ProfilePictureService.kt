package com.bernardooechsler.chirp.service

import com.bernardooechsler.chirp.domain.event.ProfilePictureUpdatedEvent
import com.bernardooechsler.chirp.domain.exception.ChatParticipantNotFoundException
import com.bernardooechsler.chirp.domain.exception.InvalidProfilePictureException
import com.bernardooechsler.chirp.domain.models.ProfilePictureUploadCredentials
import com.bernardooechsler.chirp.domain.type.UserId
import com.bernardooechsler.chirp.infra.database.repositories.ChatParticipantRepository
import com.bernardooechsler.chirp.infra.storage.SupabaseStorageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProfilePictureService(
    private val supabaseStorageService: SupabaseStorageService,
    private val chatParticipantRepository: ChatParticipantRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,  // For WebSocket notifications
    @param:Value("\${supabase.url}") private val supabaseUrl: String,
) {

    private val logger = LoggerFactory.getLogger(ProfilePictureService::class.java)

    // Step 1 of upload flow: generate credentials for the client
    // No @Transactional needed — no DB writes here
    fun generateUploadCredentials(
        userId: UserId,
        mimeType: String,
    ): ProfilePictureUploadCredentials {
        return supabaseStorageService.generateSignedUploadUrl(
            userId = userId,
            mimeType = mimeType
        )
    }

    @Transactional
    fun deleteProfilePicture(userId: UserId) {
        // Find the participant (user's chat presence record)
        val participant = chatParticipantRepository.findByIdOrNull(userId)
            ?: throw ChatParticipantNotFoundException(userId)

        // Only proceed if they actually have a profile picture
        participant.profilePictureUrl?.let { url ->
            // 1. Clear the URL in the database first
            chatParticipantRepository.save(
                participant.apply { profilePictureUrl = null }
            )

            // 2. Delete the actual file from Supabase Storage
            supabaseStorageService.deleteFile(url)

            // 3. Notify via WebSocket so other clients see the change
            applicationEventPublisher.publishEvent(
                ProfilePictureUpdatedEvent(
                    userId = userId,
                    newUrl = null  // null signals "picture removed"
                )
            )
        }
    }

    // Step 2 of upload flow: client calls this AFTER successfully uploading to Supabase
    @Transactional
    fun confirmProfilePictureUpload(userId: UserId, publicUrl: String) {
        if (!publicUrl.startsWith(supabaseUrl)) {
            throw InvalidProfilePictureException("Invalid profile picture URL")
        }

        val participant = chatParticipantRepository.findByIdOrNull(userId)
            ?: throw ChatParticipantNotFoundException(userId)

        val oldUrl = participant.profilePictureUrl  // Save reference before overwriting

        // 1. Update database with the new URL
        chatParticipantRepository.save(
            participant.apply { profilePictureUrl = publicUrl }
        )

        // 2. Clean up the old image (if one existed)
        // Wrapped in try-catch because we don't want cleanup failure to break the upload
        // The new picture is already saved — old file cleanup is "best effort"
        try {
            oldUrl?.let {
                supabaseStorageService.deleteFile(oldUrl)
            }
        } catch (e: Exception) {
            // Log but don't throw — leaves an orphan file, but user experience is intact
            logger.warn("Deleting old profile picture for $userId failed", e)
        }

        // 3. Broadcast the change to connected WebSocket clients
        applicationEventPublisher.publishEvent(
            ProfilePictureUpdatedEvent(
                userId = userId,
                newUrl = publicUrl
            )
        )
    }
}