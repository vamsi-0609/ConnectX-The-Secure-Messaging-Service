package com.connectx.app.data.remote.conversation.model

import com.connectx.app.data.remote.user.model.PublicUserDto
import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.conversation.dto.ConversationMemberDto exactly. `user` is a
 * genuine, exact reuse of PublicUserDto (data/remote/user/model/) -- the backend
 * embeds the real PublicUserDto.fromEntity(...) result verbatim here, not a
 * similarly-named-but-different shape, so no duplicate model was created for it.
 * `role` is kept as a plain nullable String (OWNER/ADMIN/MEMBER, null for DIRECT
 * members), not an Android enum -- consistent with N2.2/N2.3's convention of not
 * creating brittle enums for backend enum-name strings that could gain new values.
 */
@Serializable
data class ConversationMemberDto(
    val id: Long,
    val user: PublicUserDto? = null,
    val joinedAt: String? = null,
    val lastReadMessageId: Long? = null,
    val pinned: Boolean = false,
    val pinnedAt: String? = null,
    val mutedUntil: String? = null,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val archivedAt: String? = null,
    val manuallyMarkedUnread: Boolean = false,
    val role: String? = null
)
