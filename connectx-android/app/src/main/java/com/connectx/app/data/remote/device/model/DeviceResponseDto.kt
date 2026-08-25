package com.connectx.app.data.remote.device.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.device.dto.DeviceResponseDto exactly. `createdAt`/`lastSeenAt`
 * kept as raw ISO-8601 strings, same convention as every other timestamp field
 * across N2.1-N2.6 -- no additional date/time dependency introduced.
 */
@Serializable
data class DeviceResponseDto(
    val id: Long,
    val userId: Long,
    val deviceName: String? = null,
    val publicKey: String? = null,
    val keyAlgorithm: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val active: Boolean = false
)
