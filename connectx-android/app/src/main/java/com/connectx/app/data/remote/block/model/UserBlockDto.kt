package com.connectx.app.data.remote.block.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.block.dto.UserBlockDto exactly -- deliberately excludes
 * email, mirroring ConnectionRequestDto's rationale (see backend class javadoc).
 * Flat shape, not a nested user object.
 */
@Serializable
data class UserBlockDto(
    val id: Long,
    val blockedUserId: Long,
    val blockedUsername: String? = null,
    val blockedDisplayName: String? = null,
    val blockedProfileImageUrl: String? = null,
    val createdAt: String? = null
)
