package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.CreateGroupInvitationResponseDto exactly.
 * `outcome` is one of "DIRECT_ADDED" (target added immediately -- `invitation` is
 * null) or "INVITATION_SENT" (`invitation` is populated). A DENIED decision never
 * reaches this DTO on the backend -- it surfaces as an error response instead, so
 * no third outcome value is modeled here.
 */
@Serializable
data class CreateGroupInvitationResponseDto(
    val outcome: String? = null,
    val invitation: GroupInvitationDto? = null
)
