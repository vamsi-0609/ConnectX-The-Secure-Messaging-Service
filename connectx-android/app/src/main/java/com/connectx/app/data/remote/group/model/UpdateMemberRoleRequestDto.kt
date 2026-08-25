package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.UpdateMemberRoleRequestDto exactly. Backend field
 * type is the GroupRole Java enum (OWNER/ADMIN/MEMBER) but is kept as a plain
 * String here, consistent with the established convention of not creating
 * Android enums for backend enum-name strings. Backend @NotNull -- required.
 */
@Serializable
data class UpdateMemberRoleRequestDto(
    val role: String
)
