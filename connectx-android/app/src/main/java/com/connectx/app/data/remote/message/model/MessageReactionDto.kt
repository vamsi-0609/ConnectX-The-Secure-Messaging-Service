package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.message.dto.MessageReactionDto exactly. Flat shape (plain
 * userId/username, not a nested PublicUserDto) -- no reuse applied since the
 * backend genuinely returns a different, smaller shape here.
 */
@Serializable
data class MessageReactionDto(
    val id: Long,
    val messageId: Long,
    val userId: Long? = null,
    val username: String? = null,
    val reaction: String? = null,
    val createdAt: String? = null
)
