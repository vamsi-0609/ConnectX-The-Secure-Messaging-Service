package com.connectx.app.data.remote.media.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.media.dto.MediaUploadResponseDto exactly. No reuse of any
 * existing model was applicable -- this is a genuinely distinct flat shape.
 * `nonce`/`groupKeyVersion` are opaque/scalar E2EE metadata (GROUP media only,
 * null for DIRECT) -- no crypto behavior implemented here, per N7's boundary.
 */
@Serializable
data class MediaUploadResponseDto(
    val mediaId: Long,
    val conversationId: Long,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0,
    val nonce: String? = null,
    val groupKeyVersion: Int? = null
)
