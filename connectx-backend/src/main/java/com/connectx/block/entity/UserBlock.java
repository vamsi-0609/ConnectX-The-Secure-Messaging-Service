package com.connectx.block.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * Maps to the user_blocks table (see connectx-backend/db/migrations/V1__connection_and_group_schema.sql).
 * Directional: one row per (blocker, blocked) pair. The unique constraint and CHECK are declared
 * here (matching the names Stage 0B's migration script already created) so ddl-auto=update finds
 * them already present against connectx_db, and ddl-auto=create-drop generates the same real
 * protection from scratch against connectx_test_db -- mirrors the exact pattern already used by
 * UserConnection for the connections table.
 */
@Entity
@Table(name = "user_blocks",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_blocks_pair", columnNames = {"blocker_id", "blocked_id"})
    }
)
@Check(name = "chk_user_blocks_not_self", constraints = "blocker_id <> blocked_id")
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UserBlock() {}

    public UserBlock(User blocker, User blocked) {
        this.blocker = blocker;
        this.blocked = blocked;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getBlocker() {
        return blocker;
    }

    public void setBlocker(User blocker) {
        this.blocker = blocker;
    }

    public User getBlocked() {
        return blocked;
    }

    public void setBlocked(User blocked) {
        this.blocked = blocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
