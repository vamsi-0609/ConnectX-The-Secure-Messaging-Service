package com.connectx.app.core.network.model

import kotlinx.serialization.Serializable

/**
 * Mirrors the backend's com.connectx.common.response.ApiResponse<T> exactly
 * (connectx-backend/src/main/java/com/connectx/common/response/ApiResponse.java).
 * Every ConnectX REST endpoint that isn't a raw binary/Resource response wraps
 * its payload in this shape.
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val code: String? = null,
    val message: String? = null,
    val data: T? = null,
    val timestamp: String? = null
)
