package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.message.dto.SendMessageRequestDto exactly. `messageType`
 * is kept as a plain nullable String (backend enum MessageType, default "TEXT" if
 * omitted server-side) rather than an Android enum, matching the convention
 * established in N2.4 for backend enum-name strings. ciphertext/nonce/
 * encryptionAlgorithm are opaque strings -- no crypto behavior implemented here,
 * this is a contract-only model (encryption itself is N7's responsibility).
 */
@Serializable
data class SendMessageRequestDto(
    val conversationId: Long,
    val messageType: String? = null,
    val mediaId: Long? = null,
    val caption: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationLabel: String? = null,
    val senderDeviceId: Long? = null,
    val recipientDeviceId: Long? = null,
    val encryptionAlgorithm: String? = null,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val groupKeyVersion: Int? = null,
    val requestId: String? = null,
    val replyToMessageId: Long? = null,
    val forwarded: Boolean = false
)
