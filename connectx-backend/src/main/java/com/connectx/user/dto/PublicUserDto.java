package com.connectx.user.dto;

import com.connectx.user.entity.User;
import java.time.Instant;

/**
 * The subset of a user's profile that other authenticated users are allowed to see -- deliberately
 * excludes email, which is private (see UserDto for the self-only, full-detail equivalent). Used
 * for GET /users/{id}, user search, and every conversation member listing (ConversationMemberDto).
 */
public class PublicUserDto {

    private Long id;
    private String username;
    private String displayName;
    private String profileImageUrl;
    private String status;
    private Instant lastSeenAt;
    private Instant createdAt;
    private String profilePhotoVisibility;

    public PublicUserDto() {}

    // includeProfilePhoto is resolved by the caller via ProfileVisibilityService -- this class has
    // no notion of "who's asking", same convention as UserDto.fromEntity(User, boolean).
    public static PublicUserDto fromEntity(User user, boolean includeProfilePhoto) {
        PublicUserDto dto = new PublicUserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setProfileImageUrl(includeProfilePhoto ? user.getProfileImageUrl() : null);
        dto.setStatus(user.getStatus() != null ? user.getStatus() : "OFFLINE");
        dto.setLastSeenAt(user.getLastSeenAt());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setProfilePhotoVisibility(user.getProfilePhotoVisibility() != null ? user.getProfilePhotoVisibility() : "EVERYONE");
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
