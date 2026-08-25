package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.UpdateGroupSettingsRequestDto exactly. All three
 * policy fields are optional -- request should contain ONLY the settings being
 * changed; a null/omitted field is left untouched server-side. Kept as plain
 * Strings, matching GroupDto's convention for the same three enum-backed fields.
 */
@Serializable
data class UpdateGroupSettingsRequestDto(
    val whoCanInvite: String? = null,
    val whoCanSendMessages: String? = null,
    val whoCanEditGroupInfo: String? = null
)
