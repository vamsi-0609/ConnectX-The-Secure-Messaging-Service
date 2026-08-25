package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.auth.dto.RegisterRequest exactly.
 * Backend validation (not enforced client-side here): username 3-50 chars,
 * email must be a valid address, password min 6 chars -- all three required.
 * displayName is optional/nullable on the backend (no validation annotation).
 */
@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null
)
