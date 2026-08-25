package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.auth.dto.LoginRequest exactly.
 * Both fields required on the backend (@NotBlank); usernameOrEmail accepts
 * either a username or an email address, per the backend's field name/service logic.
 */
@Serializable
data class LoginRequest(
    val usernameOrEmail: String,
    val password: String
)
