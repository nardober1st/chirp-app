package com.bernardooechsler.chirp.api.mappers

import com.bernardooechsler.chirp.api.dto.PictureUploadResponse
import com.bernardooechsler.chirp.domain.models.ProfilePictureUploadCredentials

fun ProfilePictureUploadCredentials.toResponse(): PictureUploadResponse {
    return PictureUploadResponse(
        uploadUrl = uploadUrl,
        publicUrl = publicUrl,
        headers = headers,
        expiresAt = expiresAt
    )
}