package com.connectx.group.dto;

import com.connectx.group.entity.GroupInvitation;
import com.connectx.user.dto.PublicUserDto;

import java.time.Instant;

public class GroupInvitationDto {

    private Long id;
    private Long groupId;
    private String groupName;
    private PublicUserDto invitee;
    private PublicUserDto invitedBy;
    private String status;
    private Instant createdAt;
    private Instant respondedAt;

    public GroupInvitationDto() {}

    // photoVisible* resolved by the caller (mirrors ConversationMemberDto/PublicUserDto's
    // convention) since visibility depends on the viewer, which this DTO layer has no notion of.
    public static GroupInvitationDto fromEntity(GroupInvitation invitation, String groupName,
                                                 boolean inviteePhotoVisible, boolean invitedByPhotoVisible) {
        GroupInvitationDto dto = new GroupInvitationDto();
        dto.setId(invitation.getId());
        dto.setGroupId(invitation.getGroup().getId());
        dto.setGroupName(groupName);
        dto.setInvitee(PublicUserDto.fromEntity(invitation.getInvitee(), inviteePhotoVisible));
        dto.setInvitedBy(PublicUserDto.fromEntity(invitation.getInvitedBy(), invitedByPhotoVisible));
        dto.setStatus(invitation.getStatus().name());
        dto.setCreatedAt(invitation.getCreatedAt());
        dto.setRespondedAt(invitation.getRespondedAt());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public PublicUserDto getInvitee() {
        return invitee;
    }

    public void setInvitee(PublicUserDto invitee) {
        this.invitee = invitee;
    }

    public PublicUserDto getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(PublicUserDto invitedBy) {
        this.invitedBy = invitedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}
