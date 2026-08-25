package com.connectx.app.data.remote.user.model

import kotlinx.serialization.Serializable

/**
 * The backend accepts a raw Map<String,String> body for this endpoint
 * (UserController#verifyEmailChangeOtp reads body.get("newEmail")/body.get("otpCode"))
 * rather than a formal DTO class -- this typed model produces the identical
 * two-key JSON shape.
 */
@Serializable
data class VerifyEmailChangeOtpRequest(
    val newEmail: String,
    val otpCode: String
)
