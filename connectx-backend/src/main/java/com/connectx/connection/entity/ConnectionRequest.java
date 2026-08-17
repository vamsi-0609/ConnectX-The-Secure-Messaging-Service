package com.connectx.connection.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * Maps to connection_requests (see connectx-backend/db/migrations/V1__connection_and_group_schema.sql).
 * The table's pending_pair_key generated column and its partial-unique-on-PENDING index are
 * deliberately not mapped here -- a filtered/partial unique constraint isn't expressible via
 * plain JPA annotations, so ddl-auto can't generate it. connectx_db already has it from the
 * Stage 0B migration script; the race-condition test that depends on it
 * (ConnectionRequestRaceIntegrationTest) adds the equivalent DDL to connectx_test_db directly,
 * since that database is rebuilt from entity metadata alone on every run.
 */
@Entity
@Table(name = "connection_requests")
@Check(name = "chk_connreq_not_self", constraints = "requester_id <> recipient_id")
public class ConnectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionRequestStatus status = ConnectionRequestStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public ConnectionRequest() {}

    public ConnectionRequest(User requester, User recipient) {
        this.requester = requester;
        this.recipient = recipient;
        this.status = ConnectionRequestStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getRequester() {
        return requester;
    }

    public void setRequester(User requester) {
        this.requester = requester;
    }

    public User getRecipient() {
        return recipient;
    }

    public void setRecipient(User recipient) {
        this.recipient = recipient;
    }

    public ConnectionRequestStatus getStatus() {
        return status;
    }

    public void setStatus(ConnectionRequestStatus status) {
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
