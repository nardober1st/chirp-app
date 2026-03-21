package com.bernardooechsler.chirp.api.dto

import com.bernardooechsler.chirp.api.util.Password
import jakarta.validation.constraints.NotBlank

data class ChangePasswordRequest(
    @field:NotBlank
    val oldPassword: String,
    @field:Password
    val newPassword: String
)