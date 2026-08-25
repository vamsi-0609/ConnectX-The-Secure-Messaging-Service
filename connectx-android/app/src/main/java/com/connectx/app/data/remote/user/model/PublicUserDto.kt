package com.connectx.app.data.remote.user.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.user.dto.PublicUserDto exactly -- the subset of a user's
 * profile visible to OTHER authenticated users (no email, unlike the full UserDto
 * returned for /me). Used by search results and GET /users/{userId}.
 * lastSeenAt/createdAt kept as raw ISO-8601 strings, same convention as N2.1's UserDto.
 */
@Serializable
data class PublicUserDto(
    val id: Long,
    val username: String,
    val displayName: String? = null,
    val profileImageUrl: String? = null,
    val status: String? = null,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    val profilePhotoVisibility: String? = null
)
