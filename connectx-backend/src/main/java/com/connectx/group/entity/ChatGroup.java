package com.connectx.group.entity;

import com.connectx.conversation.entity.Conversation;
import com.connectx.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Maps to chat_groups (see connectx-backend/db/migrations/V1__connection_and_group_schema.sql).
 * Shares its primary key with conversations.id via @MapsId rather than having its own surrogate
 * identity, so "conversationId" stays the single universal handle the rest of the app already uses
 * everywhere (ConversationMember, MessageService, WebSocket topics, ...) -- a GROUP is a
 * Conversation + a ChatGroup row + ConversationMember rows, not a parallel hierarchy.
 */
@Entity
@Table(name = "chat_groups")
public class ChatGroup {

    @Id
    @Column(name = "conversation_id")
    private Long conversationId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "who_can_invite", nullable = false, length = 20)
    private WhoCanInvite whoCanInvite = WhoCanInvite.OWNER_ADMIN_ONLY;

    // Groups Stage 4 (V4__group_settings_and_user_privacy.sql) -- established/validated only;
    // actual MessageService enforcement belongs to the Group Messaging stage.
    @Enumerated(EnumType.STRING)
    @Column(name = "who_can_send_messages", nullable = false, length = 20)
    private WhoCanSendMessages whoCanSendMessages = WhoCanSendMessages.EVERYONE;

    // Groups Stage 4 (V4__group_settings_and_user_privacy.sql) -- established/validated only; the
    // group-info-edit endpoint this is meant to gate is out of scope until a future stage.
    @Enumerated(EnumType.STRING)
    @Column(name = "who_can_edit_group_info", nullable = false, length = 20)
    private WhoCanEditGroupInfo whoCanEditGroupInfo = WhoCanEditGroupInfo.OWNER_ADMIN_ONLY;

    // Groups Stage 6B (V5__group_e2ee_key_model.sql) -- authoritative current group-key version
    // for the approved shared-AES-256-group-key E2EE design (docs/CONNECTX_GROUP_ARCHITECTURE.md
    // §21). No key generation, wrapping, or rotation exists yet -- this field only establishes the
    // persistence/versioning model. Every group (new or pre-existing) resolves to 1 until a later
    // stage implements rotation.
    @Column(name = "key_version", nullable = false)
    private int keyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public ChatGroup() {}

    public ChatGroup(Conversation conversation, String name, String description, User createdByUser) {
        this.conversation = conversation;
        this.name = name;
        this.description = description;
        this.createdByUser = createdByUser;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public User getCreatedByUser() {
        return createdByUser;
    }

    public void setCreatedByUser(User createdByUser) {
        this.createdByUser = createdByUser;
    }

    public WhoCanInvite getWhoCanInvite() {
        return whoCanInvite;
    }

    public void setWhoCanInvite(WhoCanInvite whoCanInvite) {
        this.whoCanInvite = whoCanInvite;
    }

    public WhoCanSendMessages getWhoCanSendMessages() {
        return whoCanSendMessages;
    }

    public void setWhoCanSendMessages(WhoCanSendMessages whoCanSendMessages) {
        this.whoCanSendMessages = whoCanSendMessages;
    }

    public WhoCanEditGroupInfo getWhoCanEditGroupInfo() {
        return whoCanEditGroupInfo;
    }

    public void setWhoCanEditGroupInfo(WhoCanEditGroupInfo whoCanEditGroupInfo) {
        this.whoCanEditGroupInfo = whoCanEditGroupInfo;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
