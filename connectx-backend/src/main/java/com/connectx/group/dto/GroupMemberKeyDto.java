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

    public GroupMemberKeyDto() {}

    public GroupMemberKeyDto(Long groupId, int keyVersion, String wrappedKey, String wrapNonce) {
        this.groupId = groupId;
        this.keyVersion = keyVersion;
        this.wrappedKey = wrappedKey;
        this.wrapNonce = wrapNonce;
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
}
