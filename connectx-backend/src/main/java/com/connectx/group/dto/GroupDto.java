package com.connectx.group.dto;

import java.time.Instant;

public class GroupDto {

    private Long id;
    private String type;
    private String name;
    private String description;
    private String avatarUrl;
    private String whoCanInvite;
    private Long createdByUserId;
    private String currentUserRole;
    private long activeMemberCount;
    private Instant createdAt;
    private Instant updatedAt;

    public GroupDto() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getWhoCanInvite() {
        return whoCanInvite;
    }

    public void setWhoCanInvite(String whoCanInvite) {
        this.whoCanInvite = whoCanInvite;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getCurrentUserRole() {
        return currentUserRole;
    }

    public void setCurrentUserRole(String currentUserRole) {
        this.currentUserRole = currentUserRole;
    }

    public long getActiveMemberCount() {
        return activeMemberCount;
    }

    public void setActiveMemberCount(long activeMemberCount) {
        this.activeMemberCount = activeMemberCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
