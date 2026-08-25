package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.CreateGroupRequestDto exactly. Deliberately
 * carries only what a client may influence -- no creatorUserId, no role, no
 * whoCanInvite; the creator is always the authenticated caller and is always
 * assigned OWNER server-side.
 */
@Serializable
data class CreateGroupRequestDto(
    val name: String,
    val description: String? = null
)
