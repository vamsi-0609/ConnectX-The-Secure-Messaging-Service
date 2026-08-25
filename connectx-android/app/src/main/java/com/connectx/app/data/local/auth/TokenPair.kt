package com.connectx.app.data.local.auth

/**
 * Storage-local representation of a complete access+refresh token pair.
 *
 * Deliberately NOT `data.remote.auth.model.AuthResponse` (or a subset of it) --
 * `AuthResponse` also carries `tokenType`/`user` (a `UserDto`) and is a
 * `@Serializable` network DTO shaped by the backend's JSON contract. Coupling the
 * local storage layer directly to that network model would mean any unrelated
 * change to `AuthResponse` (e.g. a new user field) risks touching storage code
 * that has nothing to do with it, and would leak the kotlinx.serialization
 * network layer into a component that only ever reads/writes two plain strings.
 * This tiny model is the entire boundary between the two.
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String
)
