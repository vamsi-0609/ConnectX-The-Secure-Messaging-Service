package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors the backend's raw `POST /api/v1/auth/refresh` body exactly --
 * `AuthController#refresh` reads a plain `Map<String, String>` with one key
 * (`{"refreshToken": "..."}`), not a typed request DTO on the backend side.
 * Modeled here as a proper `@Serializable` data class (encodes to the identical
 * JSON shape) rather than passing a raw `Map` through Retrofit, per N3.0's
 * recorded open item (docs Section 35 §19-20, point 4).
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)
