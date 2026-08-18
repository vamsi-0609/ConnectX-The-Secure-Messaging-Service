package com.connectx.group.dto;

/**
 * outcome is one of "DIRECT_ADDED" (target was added to the group immediately -- invitation is
 * null) or "INVITATION_SENT" (a PENDING GroupInvitation was created -- invitation is populated).
 * A DENIED decision never reaches this DTO; it surfaces as a thrown ApiException instead.
 */
public class CreateGroupInvitationResponseDto {

    private String outcome;
    private GroupInvitationDto invitation;

    public CreateGroupInvitationResponseDto() {}

    public CreateGroupInvitationResponseDto(String outcome, GroupInvitationDto invitation) {
        this.outcome = outcome;
        this.invitation = invitation;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public GroupInvitationDto getInvitation() {
        return invitation;
    }

    public void setInvitation(GroupInvitationDto invitation) {
        this.invitation = invitation;
    }
}
