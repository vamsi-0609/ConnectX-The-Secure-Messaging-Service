package com.connectx.group.dto;

import com.connectx.conversation.entity.GroupRole;
import jakarta.validation.constraints.NotNull;

/**
 * Deliberately carries only the requested role -- no actorUserId, no currentActorRole. The actor
 * is always the authenticated principal (see GroupMembershipController); GroupAuthorizationService
 * re-derives the actor's own role from the DB and independently rejects OWNER as a requested
 * target role regardless of what's sent here.
 */
public class UpdateMemberRoleRequestDto {

    @NotNull(message = "role is required")
    private GroupRole role;

    public UpdateMemberRoleRequestDto() {}

    public UpdateMemberRoleRequestDto(GroupRole role) {
        this.role = role;
    }

    public GroupRole getRole() {
        return role;
    }

    public void setRole(GroupRole role) {
        this.role = role;
    }
}
