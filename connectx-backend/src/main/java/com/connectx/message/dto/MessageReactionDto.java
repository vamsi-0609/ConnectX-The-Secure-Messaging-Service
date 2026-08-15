package com.connectx.message.dto;

import com.connectx.message.entity.MessageReaction;
import java.time.Instant;

public class MessageReactionDto {

    private Long id;
    private Long messageId;
    private Long userId;
    private String username;
    private String reaction;
    private Instant createdAt;

    public MessageReactionDto() {}

    public static MessageReactionDto fromEntity(MessageReaction entity) {
        MessageReactionDto dto = new MessageReactionDto();
        dto.setId(entity.getId());
        dto.setMessageId(entity.getMessage().getId());
        if (entity.getUser() != null) {
            dto.setUserId(entity.getUser().getId());
            dto.setUsername(entity.getUser().getUsername());
        }
        dto.setReaction(entity.getReaction());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getReaction() {
        return reaction;
    }

    public void setReaction(String reaction) {
        this.reaction = reaction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
