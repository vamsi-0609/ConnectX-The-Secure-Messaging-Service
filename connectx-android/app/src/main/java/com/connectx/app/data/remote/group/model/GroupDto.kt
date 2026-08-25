package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.GroupDto exactly. `whoCanInvite`/
 * `whoCanSendMessages`/`whoCanEditGroupInfo`/`currentUserRole` are kept as plain
 * nullable Strings, not Android enums, consistent with the established
 * String-for-backend-enum convention (see ConversationMemberDto.role's doc
 * comment) -- backend values are WhoCanInvite{OWNER_ADMIN_ONLY,ALL_MEMBERS},
 * WhoCanSendMessages{EVERYONE,ADMINS_ONLY}, WhoCanEditGroupInfo{OWNER_ADMIN_ONLY,
 * ALL_MEMBERS}, and GroupRole{OWNER,ADMIN,MEMBER} respectively. `keyVersion` is
 * the group's authoritative E2EE key version counter only -- never accompanied
 * by key material in this DTO (see GroupMemberKeyDto for that).
 */
@Serializable
data class GroupDto(
    val id: Long,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val whoCanInvite: String? = null,
    val whoCanSendMessages: String? = null,
    val whoCanEditGroupInfo: String? = null,
    val createdByUserId: Long? = null,
    val currentUserRole: String? = null,
    val activeMemberCount: Long = 0,
    val keyVersion: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
