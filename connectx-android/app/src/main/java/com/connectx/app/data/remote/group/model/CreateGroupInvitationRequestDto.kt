package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.CreateGroupInvitationRequestDto exactly.
 * Deliberately carries only the target user id -- no inviterId, role, or status;
 * every authorization question (permission, blocking, capacity, connection) is
 * decided server-side. Backend @NotNull -- required.
 */
@Serializable
data class CreateGroupInvitationRequestDto(
    val targetUserId: Long
)
