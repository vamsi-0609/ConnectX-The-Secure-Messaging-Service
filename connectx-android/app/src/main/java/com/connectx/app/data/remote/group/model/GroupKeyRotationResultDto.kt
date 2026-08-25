package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.GroupKeyRotationResultDto exactly. Response for
 * POST .../keys/rotate-for-recovery -- carries only the newly claimed
 * server-authoritative key version, never key material. Distinct from
 * GroupKeyRequestResultDto (a reconciliation request, not a rotation) so the two
 * response shapes can never be confused.
 */
@Serializable
data class GroupKeyRotationResultDto(
    val keyVersion: Int
)
