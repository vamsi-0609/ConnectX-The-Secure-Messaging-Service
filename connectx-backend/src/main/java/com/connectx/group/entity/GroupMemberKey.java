package com.connectx.group.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Maps to group_member_keys (see connectx-backend/db/migrations/V5__group_e2ee_key_model.sql).
 * One row per (group, member): that member's opaque, ECDH-wrapped copy of the group's CURRENT
 * AES-256 key -- the approved shared-group-key E2EE design
 * (docs/CONNECTX_GROUP_ARCHITECTURE.md §21). The row is overwritten in place on key rotation (a
 * later stage), not versioned history.
 * <p>
 * {@code conversationId}/{@code memberUserId} are plain ids rather than entity associations,
 * matching {@code ConversationMember.invitedByUserId}'s established convention on this codebase --
 * the DB-level FK still enforces referential integrity; nothing here needs to load the full
 * ChatGroup/User graph.
 * <p>
 * Deliberately holds ONLY opaque wrapped key material ({@code wrappedKey}, {@code wrapNonce}) --
 * no plaintext group key, private key, or other secret is ever stored server-side. This stage
 * (6B) establishes the persistence model only; no code generates, wraps, or reads these values
 * yet.
 */
@Entity
@Table(name = "group_member_keys", uniqueConstraints = {
    @UniqueConstraint(name = "uk_groupmemberkey_conversation_member", columnNames = {"conversation_id", "member_user_id"})
})
public class GroupMemberKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "member_user_id", nullable = false)
    private Long memberUserId;

    @Lob
    @Column(name = "wrapped_key", nullable = false, columnDefinition = "TEXT")
    private String wrappedKey;

    @Lob
    @Column(name = "wrap_nonce", nullable = false, columnDefinition = "TEXT")
    private String wrapNonce;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    // Groups E2EE messaging stage (V6__group_member_key_wrapper.sql) -- the user whose client
    // wrapped this row's key material. Required for ECDH unwrap: the unwrapping member must derive
    // the SAME shared secret the wrapper used, which needs the wrapper's public key, not just the
    // opaque wrapped bytes. Always set server-side from the authenticated submitting principal
    // (see GroupKeyService#submitWrappedKey), never client-supplied.
    @Column(name = "wrapped_by_user_id")
    private Long wrappedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = Instant.now();
    }

    public GroupMemberKey() {}

    public GroupMemberKey(Long conversationId, Long memberUserId, String wrappedKey, String wrapNonce, int keyVersion) {
        this.conversationId = conversationId;
        this.memberUserId = memberUserId;
        this.wrappedKey = wrappedKey;
        this.wrapNonce = wrapNonce;
        this.keyVersion = keyVersion;
    }

    public GroupMemberKey(Long conversationId, Long memberUserId, String wrappedKey, String wrapNonce, int keyVersion, Long wrappedByUserId) {
        this(conversationId, memberUserId, wrappedKey, wrapNonce, keyVersion);
        this.wrappedByUserId = wrappedByUserId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
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

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public Long getWrappedByUserId() {
        return wrappedByUserId;
    }

    public void setWrappedByUserId(Long wrappedByUserId) {
        this.wrappedByUserId = wrappedByUserId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
