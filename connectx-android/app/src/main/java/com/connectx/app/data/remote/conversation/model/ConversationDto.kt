package com.connectx.app.data.remote.conversation.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.conversation.dto.ConversationDto exactly. `type` and
 * `lastMessageType` are plain nullable Strings (DIRECT/GROUP; TEXT/IMAGE/LOCATION/
 * DOCUMENT), not Android enums -- same rationale as ConversationMemberDto.role.
 *
 * Note: last-message information here is a set of FLAT scalar fields
 * (lastMessageId/lastMessageSenderUserId/lastMessageSentAt/...), NOT a nested
 * Message/MessageDto object -- the backend never embeds a full message here, so
 * this stays entirely within N2.4's Conversation contract and does not require
 * (or become) the N2.5 Message API.
 */
@Serializable
data class ConversationDto(
    val id: Long,
    val type: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val members: List<ConversationMemberDto>? = null,
    val lastMessageId: Long? = null,
    val lastMessageSenderUserId: Long? = null,
    val lastMessageSentAt: String? = null,
    val lastMessageDeletedForEveryone: Boolean = false,
    val lastMessageType: String? = null,
    val lastMessageCaption: String? = null,
    val pinned: Boolean = false,
    val pinnedAt: String? = null,
    val muted: Boolean = false,
    val mutedUntil: String? = null,
    val archived: Boolean = false,
    val archivedAt: String? = null,
    val manuallyMarkedUnread: Boolean = false
)
