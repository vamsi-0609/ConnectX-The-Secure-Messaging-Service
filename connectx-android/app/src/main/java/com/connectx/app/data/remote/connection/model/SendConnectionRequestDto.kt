package com.connectx.app.data.remote.connection.model

import kotlinx.serialization.Serializable

/** Mirrors com.connectx.connection.dto.SendConnectionRequestDto exactly. */
@Serializable
data class SendConnectionRequestDto(
    val recipientId: Long
)
