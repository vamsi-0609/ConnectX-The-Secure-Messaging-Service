package com.connectx.group.entity;

import com.connectx.conversation.entity.Conversation;
import com.connectx.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * Maps to group_invitations (see connectx-backend/db/migrations/V1__connection_and_group_schema.sql).
 * {@code group} references {@code conversations.id} directly (the group's conversation row), not
 * {@code chat_groups.conversation_id} -- both always hold the same value for a real GROUP, but the
 * migration's own FK targets conversations, and this mapping matches that exactly (same reasoning
 * as ConnectionRequest mapping its FKs as full User associations rather than plain ids).
 * <p>
 * The table's {@code pending_invite_key} generated column and its partial-unique-on-PENDING index
 * are deliberately not mapped here -- a filtered/partial unique constraint isn't expressible via
 * plain JPA annotations, so ddl-auto can't generate it. connectx_db already has it from the
 * Stage 0B migration script; GroupInvitationRaceIntegrationTest adds the equivalent DDL to
 * connectx_test_db directly, mirroring ConnectionRequestRaceIntegrationTest's precedent for the
 * identical situation on connection_requests.
 */
@Entity
@Table(name = "group_invitations")
@Check(name = "chk_groupinv_not_self", constraints = "invitee_user_id <> invited_by_user_id")
public class GroupInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private Conversation group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitee_user_id", nullable = false)
    private User invitee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupInvitationStatus status = GroupInvitationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public GroupInvitation() {}

    public GroupInvitation(Conversation group, User invitee, User invitedBy) {
        this.group = group;
        this.invitee = invitee;
        this.invitedBy = invitedBy;
        this.status = GroupInvitationStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Conversation getGroup() {
        return group;
    }

    public void setGroup(Conversation group) {
        this.group = group;
    }

    public User getInvitee() {
        return invitee;
    }

    public void setInvitee(User invitee) {
        this.invitee = invitee;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(User invitedBy) {
        this.invitedBy = invitedBy;
    }

    public GroupInvitationStatus getStatus() {
        return status;
    }

    public void setStatus(GroupInvitationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}
