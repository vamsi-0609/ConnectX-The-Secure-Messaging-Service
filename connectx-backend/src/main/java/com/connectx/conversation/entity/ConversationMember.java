package com.connectx.conversation.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "conversation_members", indexes = {
    @Index(name = "idx_conv_user", columnList = "conversation_id, user_id")
})
public class ConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @PrePersist
    protected void onCreate() {
        this.joinedAt = Instant.now();
    }

    public ConversationMember() {}

    public ConversationMember(Conversation conversation, User user) {
        this.conversation = conversation;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(Instant clearedAt) {
        this.clearedAt = clearedAt;
    }

    public Long getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(Long lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }
}
