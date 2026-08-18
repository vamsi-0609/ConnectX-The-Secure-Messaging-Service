package com.connectx.user.dto;

import com.connectx.user.entity.User;
import java.time.Instant;

public class UserDto {

    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String profileImageUrl;
    private String status;
    private Instant lastSeenAt;
    private Instant createdAt;
    private String profilePhotoVisibility;

    public UserDto() {}

    public static UserDto fromEntity(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setDisplayName(user.getDisplayName());
        dto.setProfileImageUrl(user.getProfileImageUrl());
        dto.setStatus(user.getStatus() != null ? user.getStatus() : "OFFLINE");
        dto.setLastSeenAt(user.getLastSeenAt());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setProfilePhotoVisibility(user.getProfilePhotoVisibility() != null ? user.getProfilePhotoVisibility() : "EVERYONE");
        return dto;
    }

    /**
     * Same as fromEntity(User), except the photo is omitted when the caller has already
     * determined (via ProfileVisibilityService) that the viewer isn't allowed to see it. Used
     * for every "another user's profile" DTO; the plain fromEntity(User) above stays for
     * self-views, where visibility never applies.
     */
    public static UserDto fromEntity(User user, boolean includeProfilePhoto) {
        UserDto dto = fromEntity(user);
        if (!includeProfilePhoto) {
            dto.setProfileImageUrl(null);
        }
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getProfilePhotoVisibility() {
        return profilePhotoVisibility;
    }

    public void setProfilePhotoVisibility(String profilePhotoVisibility) {
        this.profilePhotoVisibility = profilePhotoVisibility;
    }
}
