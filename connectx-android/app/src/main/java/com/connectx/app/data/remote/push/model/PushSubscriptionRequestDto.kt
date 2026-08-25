package com.connectx.app.data.remote.push.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.push.dto.PushSubscriptionRequestDto exactly, including its
 * nested KeysDto -- this is the standard W3C Push API subscription object shape
 * (PushSubscription.toJSON()), not a ConnectX-invented structure. Only `endpoint`
 * is backend @NotBlank; `expirationTime`/`keys` are optional. `p256dh`/`auth` are
 * opaque Web Push encryption key material -- no crypto behavior implemented here.
 */
@Serializable
data class PushSubscriptionRequestDto(
    val endpoint: String,
    val expirationTime: Long? = null,
    val keys: KeysDto? = null
) {
    @Serializable
    data class KeysDto(
        val p256dh: String? = null,
        val auth: String? = null
    )
}
