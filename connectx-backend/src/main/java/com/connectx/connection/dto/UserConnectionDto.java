package com.connectx.connection.dto;

import com.connectx.connection.entity.UserConnection;
import com.connectx.user.entity.User;

import java.time.Instant;

public class UserConnectionDto {

    private Long id;
    private Long connectedUserId;
    private String connectedUsername;
    private String connectedDisplayName;
    private String connectedProfileImageUrl;
    private Instant createdAt;

    public UserConnectionDto() {}

    public static UserConnectionDto fromEntity(UserConnection entity, Long currentUserId) {
        User other = entity.getUserLow().getId().equals(currentUserId) ? entity.getUserHigh() : entity.getUserLow();

        UserConnectionDto dto = new UserConnectionDto();
        dto.setId(entity.getId());
        dto.setConnectedUserId(other.getId());
        dto.setConnectedUsername(other.getUsername());
        dto.setConnectedDisplayName(other.getDisplayName());
        dto.setConnectedProfileImageUrl(other.getProfileImageUrl());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConnectedUserId() {
        return connectedUserId;
    }

    public void setConnectedUserId(Long connectedUserId) {
        this.connectedUserId = connectedUserId;
    }

    public String getConnectedUsername() {
        return connectedUsername;
    }

    public void setConnectedUsername(String connectedUsername) {
        this.connectedUsername = connectedUsername;
    }

    public String getConnectedDisplayName() {
        return connectedDisplayName;
    }

    public void setConnectedDisplayName(String connectedDisplayName) {
        this.connectedDisplayName = connectedDisplayName;
    }

    public String getConnectedProfileImageUrl() {
        return connectedProfileImageUrl;
    }

    public void setConnectedProfileImageUrl(String connectedProfileImageUrl) {
        this.connectedProfileImageUrl = connectedProfileImageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
