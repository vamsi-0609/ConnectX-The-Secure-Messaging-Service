package com.connectx.app.data.remote.user.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.user.dto.UserIdentityKeyDto exactly. Both fields are opaque
 * strings to the server (see docs/CONNECTX_ANDROID_DEVELOPMENT.md Section 27/24 for
 * the E2EE private-key-custody discussion) -- this is a contract-only model, no
 * crypto handling exists anywhere in the Android project.
 */
@Serializable
data class UserIdentityKeyDto(
    val masterPublicKey: String? = null,
    val masterPrivateKey: String? = null
)
