package com.connectx.user.dto;

public class UserProfileUpdateDto {

    private String username;
    private String displayName;
    private String profileImageUrl;
    private String status;
    private String profilePhotoVisibility;

    public UserProfileUpdateDto() {}

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

    public String getProfilePhotoVisibility() {
        return profilePhotoVisibility;
    }

    public void setProfilePhotoVisibility(String profilePhotoVisibility) {
        this.profilePhotoVisibility = profilePhotoVisibility;
    }
}
