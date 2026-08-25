package com.connectx.app.data.remote.device.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.device.dto.UserPublicKeyDto exactly. Distinct from
 * data.remote.user.model.UserIdentityKeyDto -- that DTO is the user's single
 * account-level master key pair (from UserController), while this one is a
 * per-DEVICE public key entry (one row per registered device, from
 * DeviceController#getUserPublicKeys). Genuinely different shape and semantics,
 * so no reuse was applicable.
 */
@Serializable
data class UserPublicKeyDto(
    val deviceId: Long,
    val userId: Long,
    val deviceName: String? = null,
    val publicKey: String? = null,
    val keyAlgorithm: String? = null
)
