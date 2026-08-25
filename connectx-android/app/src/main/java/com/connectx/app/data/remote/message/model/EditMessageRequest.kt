package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * The backend accepts a raw Map<String,String> body for this endpoint
 * (MessageController#editMessage reads body.get("ciphertext")/body.get("nonce"))
 * rather than a formal DTO class -- this typed model produces the identical
 * two-key JSON shape. Opaque ciphertext/nonce strings -- no crypto behavior here.
 */
@Serializable
data class EditMessageRequest(
    val ciphertext: String? = null,
    val nonce: String? = null
)
