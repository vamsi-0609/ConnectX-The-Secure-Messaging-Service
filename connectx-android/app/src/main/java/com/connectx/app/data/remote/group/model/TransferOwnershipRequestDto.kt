package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.TransferOwnershipRequestDto exactly. Deliberately
 * carries only the target new-owner id -- no actorUserId; the actor is always the
 * authenticated caller. Backend @NotNull -- required.
 */
@Serializable
data class TransferOwnershipRequestDto(
    val newOwnerUserId: Long
)
