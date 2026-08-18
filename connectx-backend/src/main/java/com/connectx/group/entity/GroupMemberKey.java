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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
