package com.connectx.group.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Deliberately carries only the target user id -- no inviterId, no role, no membershipStatus.
 * The inviter is always the authenticated principal (see GroupInvitationController) and every
 * authorization question (permission, blocking, capacity, target state/privacy/connection) is
 * decided server-side by GroupAuthorizationService, never influenced by anything in this DTO.
 */
public class CreateGroupInvitationRequestDto {

    @NotNull(message = "targetUserId is required")
    private Long targetUserId;

    public CreateGroupInvitationRequestDto() {}

    public CreateGroupInvitationRequestDto(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }
}
