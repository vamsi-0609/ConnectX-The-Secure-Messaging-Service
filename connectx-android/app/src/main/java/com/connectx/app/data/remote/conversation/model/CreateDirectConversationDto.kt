package com.connectx.app.data.remote.conversation.model

import kotlinx.serialization.Serializable

/** Mirrors com.connectx.conversation.dto.CreateDirectConversationDto exactly. */
@Serializable
data class CreateDirectConversationDto(
    val userId: Long
)
