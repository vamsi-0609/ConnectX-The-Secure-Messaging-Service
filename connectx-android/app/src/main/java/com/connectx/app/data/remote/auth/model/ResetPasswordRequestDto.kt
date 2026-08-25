package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors `com.connectx.auth.dto.ResetPasswordRequestDto` exactly. All three
 * fields backend @NotBlank (`otpCode` also 6 digits, `newPassword` also
 * min 6 chars) -- not re-validated client-side, same convention as every
 * other request model. `newPassword` exists on this request object only for
 * the duration of the single POST call that carries it -- it is never
 * persisted, logged, or held anywhere beyond that.
 */
@Serializable
data class ResetPasswordRequestDto(
    val email: String,
    val otpCode: String,
    val newPassword: String
)
