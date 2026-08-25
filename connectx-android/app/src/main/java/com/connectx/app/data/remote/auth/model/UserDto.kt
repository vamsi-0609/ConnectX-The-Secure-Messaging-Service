package com.connectx.app.data.remote.auth.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.user.dto.UserDto exactly, as embedded in AuthResponse.user.
 * lastSeenAt/createdAt are kept as raw ISO-8601 strings (backend's Jackson config
 * disables WRITE_DATES_AS_TIMESTAMPS, confirmed via JacksonConfig.java) -- date
 * parsing is deliberately not introduced in N2.1.
 */
@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val email: String,
    val displayName: String? = null,
    val profileImageUrl: String? = null,
    val status: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val profilePhotoVisibility: String? = null,
    val groupAddPrivacy: String? = null
)
