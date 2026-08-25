package com.connectx.app.data.remote.user.model

import kotlinx.serialization.Serializable

/**
 * The backend accepts a raw Map<String,String> body for this endpoint
 * (UserController#requestEmailChangeOtp reads body.get("newEmail")) rather than a
 * formal DTO class -- this typed model produces the identical single-key JSON shape.
 */
@Serializable
data class RequestEmailChangeOtpRequest(
    val newEmail: String
)
