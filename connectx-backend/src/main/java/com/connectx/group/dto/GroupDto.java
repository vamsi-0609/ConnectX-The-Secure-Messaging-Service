package com.connectx.group.dto;

import java.time.Instant;

public class GroupDto {

    private Long id;
    private String type;
    private String name;
    private String description;
    private String avatarUrl;
    private String whoCanInvite;
    private String whoCanSendMessages;
    private String whoCanEditGroupInfo;
    private Long createdByUserId;
    private String currentUserRole;
    private long activeMemberCount;
    // The group's authoritative current E2EE key version (ChatGroup.keyVersion) -- the frontend
    // compares this against its own cached/fetched wrapped key's version to detect a rotation it
    // hasn't caught up with yet (see docs on the group key lifecycle). Never a secret by itself --
    // just a counter -- but never accompanied by any key material in this DTO.
    private int keyVersion;
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

    public String getWhoCanSendMessages() {
        return whoCanSendMessages;
    }

    public void setWhoCanSendMessages(String whoCanSendMessages) {
        this.whoCanSendMessages = whoCanSendMessages;
    }

    public String getWhoCanEditGroupInfo() {
        return whoCanEditGroupInfo;
    }

    public void setWhoCanEditGroupInfo(String whoCanEditGroupInfo) {
        this.whoCanEditGroupInfo = whoCanEditGroupInfo;
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

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
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
