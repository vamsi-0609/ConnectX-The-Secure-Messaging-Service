package com.connectx.group.entity;

/**
 * chat_groups.who_can_edit_group_info -- see V4__group_settings_and_user_privacy.sql. Established
 * and validated this stage (Groups Stage 4) as one of the three OWNER-only-mutable policy settings
 * (docs/CONNECTX_GROUP_ARCHITECTURE.md §6: "Change group settings (the 3 ENUMs) -- Owner only");
 * the actual name/description/avatar edit endpoint this setting is meant to gate is out of scope
 * until a future group-info-editing stage.
 */
public enum WhoCanEditGroupInfo {
    OWNER_ADMIN_ONLY,
    ALL_MEMBERS
}
