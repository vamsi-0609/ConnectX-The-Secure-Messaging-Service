package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.auth.dto.AuthResponse exactly. Both register and login
 * return this shape (wrapped in ApiResponse<AuthResponse>). accessToken and
 * refreshToken are structurally identical JWTs on the backend (no distinguishing
 * claim) -- see docs/CONNECTX_ANDROID_DEVELOPMENT.md Section 27 for the full
 * JWT architecture writeup. Neither token is stored or used anywhere in N2.1.
 */
@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val user: UserDto
)
