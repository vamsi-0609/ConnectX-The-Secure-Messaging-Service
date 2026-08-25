package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors `com.connectx.auth.dto.VerifyOtpRequestDto` exactly. Both fields
 * backend @NotBlank; `otpCode` additionally @Size(min=6,max=6) -- not
 * re-validated client-side here, matching every other request model's
 * convention of trusting the backend as validation authority.
 */
@Serializable
data class VerifyOtpRequestDto(
    val email: String,
    val otpCode: String
)
