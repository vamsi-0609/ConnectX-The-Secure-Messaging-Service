package com.connectx.group.entity;

/**
 * chat_groups.who_can_send_messages -- see V4__group_settings_and_user_privacy.sql. Established
 * and validated this stage (Groups Stage 4); actual MessageService enforcement is out of scope
 * until the Group Messaging stage.
 */
public enum WhoCanSendMessages {
    EVERYONE,
    ADMINS_ONLY
}
