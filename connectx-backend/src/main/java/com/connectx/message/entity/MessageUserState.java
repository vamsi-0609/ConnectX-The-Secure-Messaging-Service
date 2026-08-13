package com.connectx.message.entity;

import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message_user_state", indexes = {
    @Index(name = "idx_msg_user_state", columnList = "message_id, user_id")
})
public class MessageUserState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        if (this.deletedAt == null) {
            this.deletedAt = Instant.now();
        }
    }

    public MessageUserState() {}

    public MessageUserState(Message message, User user) {
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
