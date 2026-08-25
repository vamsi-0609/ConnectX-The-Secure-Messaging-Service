package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.UpdateGroupInfoRequestDto exactly. Deliberately
 * separate from UpdateGroupSettingsRequestDto -- the two are gated by different
 * authorization rules on the backend (who_can_edit_group_info vs. owner-only).
 * Both fields optional; a null field is left untouched, an explicit blank
 * description clears it (backend-side behavior, not enforced here).
 */
@Serializable
data class UpdateGroupInfoRequestDto(
    val name: String? = null,
    val description: String? = null
)
