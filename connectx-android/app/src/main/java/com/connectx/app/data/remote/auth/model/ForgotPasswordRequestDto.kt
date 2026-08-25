package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors `com.connectx.auth.dto.ForgotPasswordRequestDto` exactly. Backend
 * @NotBlank/@Email -- required. Email-based recovery only, matching
 * ConnectX's actual identity model (username for discovery/login, email for
 * account recovery) -- no phone number field exists on the backend, so none
 * is modeled here.
 */
@Serializable
data class ForgotPasswordRequestDto(
    val email: String
)
