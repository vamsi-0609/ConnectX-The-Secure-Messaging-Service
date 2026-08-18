package com.connectx.conversation.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "conversation_members", indexes = {
    @Index(name = "idx_conv_user", columnList = "conversation_id, user_id"),
    // conversation_id alone (leftmost prefix of idx_conv_user) doesn't help queries that
    // filter by user_id without conversation_id -- e.g. the conversation list load (every
    // app open) and presence broadcast (every WS connect/disconnect) both do.
    @Index(name = "idx_convmember_user_deleted", columnList = "user_id, deleted_at")
}, uniqueConstraints = {
    // Groups Stage 2: at most one row per (conversation, user) pair, active or soft-deleted --
    // see V3__group_membership_unique_constraint.sql for why this is needed starting this stage
    // (concurrent invitation-accept is the first source of concurrent conversation_members
    // inserts for the same pair) and confirmation it's safe against existing data. Declared here
    // so ddl-auto=update finds it already present against connectx_db (added by that script) and
    // ddl-auto=create-drop generates the same real protection from scratch against
    // connectx_test_db, matching the uk_connections_pair / uk_user_blocks_pair precedent.
    @UniqueConstraint(name = "uk_convmember_conversation_user", columnNames = {"conversation_id", "user_id"})
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

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned = false;

    @Column(name = "pinned_at")
    private Instant pinnedAt;

    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "is_archived", nullable = false)
    private boolean archived = false;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "manually_marked_unread", nullable = false)
    private boolean manuallyMarkedUnread = false;

    // Both columns are additive from V1__connection_and_group_schema.sql and stay NULL for every
    // DIRECT conversation member -- only meaningful once a member belongs to a GROUP conversation.
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20)
    private GroupRole role;

    // Plain user id rather than a User association (matches lastReadMessageId's convention on this
    // same entity) -- the DB-level FK from the Stage 0B migration still enforces referential
    // integrity; the app never needs to load the inviter's full User for this field.
    @Column(name = "invited_by_user_id")
    private Long invitedByUserId;

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

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(Instant pinnedAt) {
        this.pinnedAt = pinnedAt;
    }

    public Instant getMutedUntil() {
        return mutedUntil;
    }

    public void setMutedUntil(Instant mutedUntil) {
        this.mutedUntil = mutedUntil;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public boolean isManuallyMarkedUnread() {
        return manuallyMarkedUnread;
    }

    public void setManuallyMarkedUnread(boolean manuallyMarkedUnread) {
        this.manuallyMarkedUnread = manuallyMarkedUnread;
    }

    public GroupRole getRole() {
        return role;
    }

    public void setRole(GroupRole role) {
        this.role = role;
    }

    public Long getInvitedByUserId() {
        return invitedByUserId;
    }

    public void setInvitedByUserId(Long invitedByUserId) {
        this.invitedByUserId = invitedByUserId;
    }
}
