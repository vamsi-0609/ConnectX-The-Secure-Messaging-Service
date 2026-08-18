package com.connectx.group.dto;

/**
 * Response shape for the group-key endpoints -- deliberately not the {@code GroupMemberKey}
 * entity itself (never serialized directly). Carries only what the caller needs: which group,
 * which key version, and their own opaque wrapped key material. No internal database id, no
 * {@code memberUserId} (the response is always "yours" -- GET .../keys/me is scoped to the
 * authenticated caller, never a parameter), no {@code updatedAt}, no plaintext key of any kind.
 */
public class GroupMemberKeyDto {

    private Long groupId;
    private int keyVersion;
    private String wrappedKey;
    private String wrapNonce;
    // The user whose client wrapped this key -- the unwrapping client needs their public key
    // (fetched separately, e.g. via the existing /users/{id}/devices/public-keys) to re-derive the
    // same ECDH shared secret the wrapper used. Never the recipient's own id (that's always "me").
    private Long wrappedByUserId;

    public GroupMemberKeyDto() {}

    public GroupMemberKeyDto(Long groupId, int keyVersion, String wrappedKey, String wrapNonce, Long wrappedByUserId) {
        this.groupId = groupId;
        this.keyVersion = keyVersion;
        this.wrappedKey = wrappedKey;
        this.wrapNonce = wrapNonce;
        this.wrappedByUserId = wrappedByUserId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getWrappedKey() {
        return wrappedKey;
    }

    public void setWrappedKey(String wrappedKey) {
        this.wrappedKey = wrappedKey;
    }

    public String getWrapNonce() {
        return wrapNonce;
    }

    public void setWrapNonce(String wrapNonce) {
        this.wrapNonce = wrapNonce;
    }

    public Long getWrappedByUserId() {
        return wrappedByUserId;
    }

    public void setWrappedByUserId(Long wrappedByUserId) {
        this.wrappedByUserId = wrappedByUserId;
    }
}
