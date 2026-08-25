package com.connectx.app.core.network.model

import kotlinx.serialization.Serializable

/**
 * Mirrors the backend's com.connectx.common.response.ErrorResponse exactly
 * (connectx-backend/src/main/java/com/connectx/common/response/ErrorResponse.java),
 * as returned by GlobalExceptionHandler on every non-2xx response. This is a
 * contract-only model for N2.1 -- it is not yet wired into any error-parsing
 * logic (that belongs to N2.5).
 */
@Serializable
data class ErrorResponse(
    val timestamp: String? = null,
    val status: Int = 0,
    val code: String? = null,
    val message: String? = null,
    val path: String? = null
)
