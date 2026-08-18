package com.connectx.user.dto;

/**
 * profileImageUrl is deliberately NOT a field here: profile photos may only be set via the
 * dedicated upload endpoint (POST /users/me/profile-photo, which always derives a safe internal
 * storage path) or removed via DELETE /users/me/profile-photo. Accepting an arbitrary
 * client-supplied URL through this generic PATCH previously let a caller set an external image
 * host, which -- combined with the frontend attaching the viewer's JWT to any http(s) profile
 * image URL -- was a credential-exfiltration vector. See docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md's
 * pre-Groups security checkpoint for the full writeup.
 */
public class UserProfileUpdateDto {

    private String username;
    private String displayName;
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
