package com.connectx.app.data.remote.group.model

import com.connectx.app.data.remote.user.model.PublicUserDto
import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.GroupInvitationDto exactly. `invitee`/
 * `invitedBy` are a genuine, exact reuse of PublicUserDto (data/remote/user/model/)
 * -- the backend embeds the real PublicUserDto.fromEntity(...) result verbatim
 * here, same pattern as ConversationMemberDto's `user` field. `status` kept as a
 * plain String (backend GroupInvitationStatus: PENDING/ACCEPTED/REJECTED/
 * CANCELLED), not an Android enum, per the established convention.
 */
@Serializable
data class GroupInvitationDto(
    val id: Long,
    val groupId: Long,
    val groupName: String? = null,
    val invitee: PublicUserDto? = null,
    val invitedBy: PublicUserDto? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val respondedAt: String? = null
)
