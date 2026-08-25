package com.connectx.app.data.remote.user.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.user.dto.UserProfileUpdateDto exactly. profileImageUrl is
 * deliberately NOT a field -- the backend intentionally excludes it here (photo
 * changes only via the dedicated upload/remove endpoints; see the backend class's
 * javadoc for the security reasoning). No validation annotations exist on the
 * backend DTO, so all fields are optional/nullable here too.
 */
@Serializable
data class UserProfileUpdateDto(
    val username: String? = null,
    val displayName: String? = null,
    val status: String? = null,
    val profilePhotoVisibility: String? = null,
    val groupAddPrivacy: String? = null
)
