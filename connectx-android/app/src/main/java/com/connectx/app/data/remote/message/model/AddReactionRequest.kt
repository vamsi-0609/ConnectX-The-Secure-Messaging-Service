package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * The backend accepts a raw Map<String,String> body for this endpoint
 * (MessageController#addOrUpdateReaction reads body.get("reaction")) rather than
 * a formal DTO class -- this typed model produces the identical single-key JSON
 * shape, matching N2.2's ad-hoc-map convention.
 */
@Serializable
data class AddReactionRequest(
    val reaction: String
)
