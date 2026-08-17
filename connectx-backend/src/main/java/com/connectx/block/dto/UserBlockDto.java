package com.connectx.block.dto;

import com.connectx.block.entity.UserBlock;

import java.time.Instant;

/**
 * Deliberately excludes email, mirroring ConnectionRequestDto's rationale -- a block record
 * should not leak more about the blocked user than discovery (GET /users/search) already does.
 */
public class UserBlockDto {

    private Long id;
    private Long blockedUserId;
    private String blockedUsername;
    private String blockedDisplayName;
    private String blockedProfileImageUrl;
    private Instant createdAt;

    public UserBlockDto() {}

    public static UserBlockDto fromEntity(UserBlock entity) {
        UserBlockDto dto = new UserBlockDto();
        dto.setId(entity.getId());
        dto.setBlockedUserId(entity.getBlocked().getId());
        dto.setBlockedUsername(entity.getBlocked().getUsername());
        dto.setBlockedDisplayName(entity.getBlocked().getDisplayName());
        dto.setBlockedProfileImageUrl(entity.getBlocked().getProfileImageUrl());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBlockedUserId() {
        return blockedUserId;
    }

    public void setBlockedUserId(Long blockedUserId) {
        this.blockedUserId = blockedUserId;
    }

    public String getBlockedUsername() {
        return blockedUsername;
    }

    public void setBlockedUsername(String blockedUsername) {
        this.blockedUsername = blockedUsername;
    }

    public String getBlockedDisplayName() {
        return blockedDisplayName;
    }

    public void setBlockedDisplayName(String blockedDisplayName) {
        this.blockedDisplayName = blockedDisplayName;
    }

    public String getBlockedProfileImageUrl() {
        return blockedProfileImageUrl;
    }

    public void setBlockedProfileImageUrl(String blockedProfileImageUrl) {
        this.blockedProfileImageUrl = blockedProfileImageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
