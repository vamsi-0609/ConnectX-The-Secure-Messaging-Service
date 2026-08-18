package com.connectx.group.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Deliberately carries only the target new-owner id -- no actorUserId. The actor is always the
 * authenticated principal (see GroupMembershipController); GroupAuthorizationService re-derives
 * the actor's own role from the DB and rejects anyone but the current OWNER.
 */
public class TransferOwnershipRequestDto {

    @NotNull(message = "newOwnerUserId is required")
    private Long newOwnerUserId;

    public TransferOwnershipRequestDto() {}

    public TransferOwnershipRequestDto(Long newOwnerUserId) {
        this.newOwnerUserId = newOwnerUserId;
    }

    public Long getNewOwnerUserId() {
        return newOwnerUserId;
    }

    public void setNewOwnerUserId(Long newOwnerUserId) {
        this.newOwnerUserId = newOwnerUserId;
    }
}
