package com.connectx.conversation.entity;

/**
 * conversation_members.role -- NULL for every DIRECT conversation member (role only applies to
 * GROUP membership). See V1__connection_and_group_schema.sql.
 */
public enum GroupRole {
    OWNER,
    ADMIN,
    MEMBER
}
