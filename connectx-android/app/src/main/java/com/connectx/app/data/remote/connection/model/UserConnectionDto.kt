package com.connectx.app.data.remote.connection.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.connection.dto.UserConnectionDto exactly -- an established
 * (accepted) connection, resolved to whichever party isn't the current viewer.
 * Flat shape, not a nested user object.
 */
@Serializable
data class UserConnectionDto(
    val id: Long,
    val connectedUserId: Long,
    val connectedUsername: String? = null,
    val connectedDisplayName: String? = null,
    val connectedProfileImageUrl: String? = null,
    val createdAt: String? = null
)
