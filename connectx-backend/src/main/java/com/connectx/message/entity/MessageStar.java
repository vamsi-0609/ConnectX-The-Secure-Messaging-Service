package com.connectx.message.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message_stars",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_message_star", columnNames = {"message_id", "user_id"})
    }
)
public class MessageStar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "starred_at", nullable = false)
    private Instant starredAt;

    @PrePersist
    protected void onCreate() {
        if (this.starredAt == null) {
            this.starredAt = Instant.now();
        }
    }

    public MessageStar() {}

    public MessageStar(Message message, User user) {
        this.message = message;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Instant getStarredAt() {
        return starredAt;
    }

    public void setStarredAt(Instant starredAt) {
        this.starredAt = starredAt;
    }
}
