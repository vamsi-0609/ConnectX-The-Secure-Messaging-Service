package com.connectx.app.data.remote.conversation.model

import kotlinx.serialization.Serializable

/**
 * The backend accepts an OPTIONAL raw Map<String,String> body for the mute endpoint
 * (ConversationController#muteConversation reads body.get("mutedUntil") as an
 * ISO-8601 Instant string, tolerating a missing/unparseable value by muting
 * indefinitely) -- this typed model produces the identical single-key JSON shape,
 * matching N2.2's RequestEmailChangeOtpRequest convention for ad-hoc map bodies.
 */
@Serializable
data class MuteConversationRequest(
    val mutedUntil: String? = null
)
