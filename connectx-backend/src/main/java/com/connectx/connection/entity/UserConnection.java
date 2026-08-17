package com.connectx.connection.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;

/**
 * Maps to the connections table (see connectx-backend/db/migrations/V1__connection_and_group_schema.sql).
 * Named UserConnection rather than Connection to avoid colliding with java.sql.Connection --
 * the table itself is still named "connections".
 * <p>
 * One row per unordered pair of connected users, in canonical order (userLow.id &lt; userHigh.id,
 * enforced at the DB level by chk_connections_canonical_order). Always construct via the
 * (requesterId, recipientId) -&gt; (low, high) ordering done in the service layer -- never assume
 * either side of a ConnectionRequest maps directly to userLow/userHigh.
 * <p>
 * The unique constraint and CHECK are declared here (matching the names Stage 0B's migration
 * script already created) so ddl-auto=update finds them already present against connectx_db,
 * and so ddl-auto=create-drop generates the same real protection from scratch against
 * connectx_test_db -- without this, the test database would have no DB-level guard against a
 * duplicate/reversed-pair row, and the race-condition test in ConnectionServiceTest would not
 * actually be exercising the constraint it claims to.
 */
@Entity
@Table(name = "connections",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_connections_pair", columnNames = {"user_id_low", "user_id_high"})
    }
)
@Check(name = "chk_connections_canonical_order", constraints = "user_id_low < user_id_high")
public class UserConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id_low", nullable = false)
    private User userLow;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id_high", nullable = false)
    private User userHigh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UserConnection() {}

    public UserConnection(User userLow, User userHigh, ConnectionSource source) {
        this.userLow = userLow;
        this.userHigh = userHigh;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUserLow() {
        return userLow;
    }

    public void setUserLow(User userLow) {
        this.userLow = userLow;
    }

    public User getUserHigh() {
        return userHigh;
    }

    public void setUserHigh(User userHigh) {
        this.userHigh = userHigh;
    }

    public ConnectionSource getSource() {
        return source;
    }

    public void setSource(ConnectionSource source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
