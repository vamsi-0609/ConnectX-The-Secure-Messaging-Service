package com.connectx.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Deliberately carries only opaque cryptographic material plus the intended target -- no
 * actorUserId, no groupKey/plaintextKey/privateKey/secretKey field of any kind. The submitting
 * actor is always the authenticated principal (see GroupKeyController); {@code wrappedKey}/
 * {@code wrapNonce} are treated as completely opaque strings by every layer that touches this DTO
 * -- the backend never inspects, decrypts, or derives anything from their contents.
 */
public class SubmitGroupMemberKeyRequestDto {

    @NotNull(message = "memberUserId is required")
    private Long memberUserId;

    @NotBlank(message = "wrappedKey is required")
    private String wrappedKey;

    @NotBlank(message = "wrapNonce is required")
    private String wrapNonce;

    @NotNull(message = "keyVersion is required")
    @Positive(message = "keyVersion must be positive")
    private Integer keyVersion;

    public SubmitGroupMemberKeyRequestDto() {}

    public SubmitGroupMemberKeyRequestDto(Long memberUserId, String wrappedKey, String wrapNonce, Integer keyVersion) {
        this.memberUserId = memberUserId;
        this.wrappedKey = wrappedKey;
        this.wrapNonce = wrapNonce;
        this.keyVersion = keyVersion;
    }

    public Long getMemberUserId() {
        return memberUserId;
    }

    public void setMemberUserId(Long memberUserId) {
        this.memberUserId = memberUserId;
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

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }
}
