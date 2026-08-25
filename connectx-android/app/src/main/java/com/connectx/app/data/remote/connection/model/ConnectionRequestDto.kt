package com.connectx.app.data.remote.connection.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.connection.dto.ConnectionRequestDto exactly. A deliberately
 * flat shape (not a nested PublicUserDto/UserDto) -- the backend intentionally
 * excludes email and any field not already public via user search, per the
 * backend class's own javadoc. status is the ConnectionRequestStatus enum name
 * as a string (PENDING/ACCEPTED/REJECTED/CANCELLED). createdAt/respondedAt kept
 * as raw ISO-8601 strings, consistent with N2.1/N2.2.
 */
@Serializable
data class ConnectionRequestDto(
    val id: Long,
    val requesterId: Long,
    val requesterUsername: String? = null,
    val requesterDisplayName: String? = null,
    val requesterProfileImageUrl: String? = null,
    val recipientId: Long,
    val recipientUsername: String? = null,
    val recipientDisplayName: String? = null,
    val recipientProfileImageUrl: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val respondedAt: String? = null
)
