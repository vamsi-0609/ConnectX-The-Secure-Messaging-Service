package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.SubmitGroupMemberKeyRequestDto exactly.
 * Deliberately carries only opaque cryptographic material plus the intended
 * target -- no actorUserId, no plaintext key field of any kind. `wrappedKey`/
 * `wrapNonce` are treated as completely opaque strings -- no crypto behavior
 * implemented here, per N7's boundary. All four fields backend-required
 * (@NotNull/@NotBlank/@Positive).
 */
@Serializable
data class SubmitGroupMemberKeyRequestDto(
    val memberUserId: Long,
    val wrappedKey: String,
    val wrapNonce: String,
    val keyVersion: Int
)
