package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.message.dto.MessageDto exactly (32 fields on the backend
 * class). Notes on specific fields:
 *
 * - `messageType`/`replyToMessageType`: plain nullable String (TEXT/IMAGE/LOCATION/
 *   DOCUMENT), not an Android enum -- same convention as SendMessageRequestDto and
 *   N2.4's ConversationDto.type.
 * - `ciphertext`/`nonce`/`encryptionAlgorithm`/`mediaNonce`: opaque strings, no
 *   crypto behavior -- E2EE handling itself is N7's responsibility.
 * - `mediaId`/`mimeType`/`fileSizeBytes`/`mediaNonce`: scalar fields that ARE part
 *   of this REST contract (the backend returns them directly on MessageDto, not
 *   via a nested Media object) -- modeled here per N2.5's instructions, but no
 *   MediaApi or media upload/download behavior was implemented (that's N2.6).
 * - Reply-to fields (`replyTo*`) are flat scalars, NOT a nested MessageDto -- no
 *   self-referential model was needed.
 * - `reactions`: nested List<MessageReactionDto>.
 */
@Serializable
data class MessageDto(
    val id: Long,
    val conversationId: Long,
    val senderUserId: Long? = null,
    val senderUsername: String? = null,
    val senderDeviceId: Long? = null,
    val recipientDeviceId: Long? = null,
    val messageType: String? = null,
    val mediaId: Long? = null,
    val caption: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationLabel: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val encryptionAlgorithm: String? = null,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val groupKeyVersion: Int? = null,
    val sentAt: String? = null,
    val deliveredAt: String? = null,
    val readAt: String? = null,
    val deletedForEveryone: Boolean = false,
    val replyToMessageId: Long? = null,
    val replyToSenderUsername: String? = null,
    val replyToMessageType: String? = null,
    val replyToCaption: String? = null,
    val replyToDeleted: Boolean = false,
    val reactions: List<MessageReactionDto> = emptyList(),
    val editedAt: String? = null,
    val forwarded: Boolean = false,
    val pinnedAt: String? = null,
    val pinnedByUserId: Long? = null,
    val pinnedByUsername: String? = null,
    val starred: Boolean = false,
    val mediaNonce: String? = null
)
