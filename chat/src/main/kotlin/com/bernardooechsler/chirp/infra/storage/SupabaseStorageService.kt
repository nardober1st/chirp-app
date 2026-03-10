package com.bernardooechsler.chirp.infra.storage

import com.bernardooechsler.chirp.domain.exception.InvalidProfilePictureException
import com.bernardooechsler.chirp.domain.exception.StorageException
import com.bernardooechsler.chirp.domain.models.ProfilePictureUploadCredentials
import com.bernardooechsler.chirp.domain.type.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID

@Service
class SupabaseStorageService(
    @param:Value("\${supabase.url}") private val supabaseUrl: String,
    private val supabaseRestClient: RestClient,  // The pre-configured client from your config
) {
    companion object {
        // Whitelist of allowed image types → maps MIME type to file extension
        // This prevents users from uploading PDFs, executables, etc.
        private val allowedMimeTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
        )
    }

    fun generateSignedUploadUrl(userId: UserId, mimeType: String): ProfilePictureUploadCredentials {
        // Validate the MIME type and get the file extension
        // If mimeType isn't in our whitelist, throw an exception
        val extension = allowedMimeTypes[mimeType]
            ?: throw InvalidProfilePictureException("Invalid mime type $mimeType")

        // Build a unique filename: user_123_abc-def-ghi.png
        // UUID prevents filename collisions if user uploads multiple times
        val fileName = "user_${userId}_${UUID.randomUUID()}.$extension"
        val path = "profile-pictures/$fileName"  // Folder structure in Supabase bucket

        // This is the permanent public URL where the image will be accessible after upload
        val publicUrl = "$supabaseUrl/storage/v1/object/public/$path"

        // Return everything the client needs to perform the upload
        return ProfilePictureUploadCredentials(
            uploadUrl = createSignedUrl(path = path, expiresInSeconds = 300),  // 5-minute window
            publicUrl = publicUrl,
            headers = mapOf("Content-Type" to mimeType),  // Client must send this header
            expiresAt = Instant.now().plusSeconds(300)
        )
    }

    fun deleteFile(url: String) {
        // Extract the path from a full public URL
        // e.g., "https://xxx.supabase.co/storage/v1/object/public/profile-pictures/user_123.png"
        //       → "profile-pictures/user_123.png"
        val path = if (url.contains("/object/public/")) {
            url.substringAfter("/object/public/")
        } else throw StorageException("Invalid file URL format")

        val deleteUrl = "/storage/v1/object/$path"

        val response = supabaseRestClient
            .delete()
            .uri(deleteUrl)
            .retrieve()
            .toBodilessEntity()  // We don't need the response body, just the status

        if (response.statusCode.isError) {
            throw StorageException("Unable to delete file: ${response.statusCode.value()}")
        }
    }

    private fun createSignedUrl(path: String, expiresInSeconds: Int): String {
        // Build the JSON body manually (simple enough to not need a DTO)
        val json = """
            { "expiresIn": $expiresInSeconds }
        """.trimIndent()

        // Call Supabase's signed URL endpoint
        val response = supabaseRestClient
            .post()
            .uri("/storage/v1/object/upload/sign/$path")
            .header("Content-Type", "application/json")
            .body(json)
            .retrieve()
            .body(SignedUploadResponse::class.java)  // Deserialize JSON to our data class
            ?: throw StorageException("Failed to create signed URL")

        // Response.url is relative, so prepend the base URL
        return "$supabaseUrl/storage/v1${response.url}"
    }

    // Simple data class just for deserializing the Supabase response
    private data class SignedUploadResponse(
        val url: String
    )
}