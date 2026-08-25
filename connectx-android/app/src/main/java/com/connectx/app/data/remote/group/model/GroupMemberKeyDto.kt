package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.GroupMemberKeyDto exactly. `wrappedByUserId` is
 * the user whose client wrapped this key (needed to look up their public key via
 * DeviceApi.getUserPublicKeys before unwrapping) -- never the recipient's own id,
 * since GET .../keys/me is always scoped to the authenticated caller. No plaintext
 * key material anywhere in this DTO -- no crypto behavior implemented here.
 */
@Serializable
data class GroupMemberKeyDto(
    val groupId: Long,
    val keyVersion: Int,
    val wrappedKey: String? = null,
    val wrapNonce: String? = null,
    val wrappedByUserId: Long? = null
)
