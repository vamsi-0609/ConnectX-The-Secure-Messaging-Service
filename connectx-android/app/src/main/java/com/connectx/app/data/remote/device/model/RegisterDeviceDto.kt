package com.connectx.app.data.remote.device.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.device.dto.RegisterDeviceDto exactly. All three fields are
 * backend @NotBlank -- required, non-nullable here. `publicKey`/`keyAlgorithm` are
 * opaque E2EE metadata (e.g. "ECDH-P256") -- no crypto behavior implemented here,
 * per N7's boundary.
 */
@Serializable
data class RegisterDeviceDto(
    val deviceName: String,
    val publicKey: String,
    val keyAlgorithm: String
)
