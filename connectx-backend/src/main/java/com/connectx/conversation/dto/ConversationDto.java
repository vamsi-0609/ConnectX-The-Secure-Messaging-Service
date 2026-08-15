package com.connectx.conversation.dto;

import com.connectx.conversation.entity.Conversation;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class ConversationDto {

    private Long id;
    private String type;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ConversationMemberDto> members;
    private Long lastMessageId;
    private Long lastMessageSenderUserId;
    private Instant lastMessageSentAt;
    private boolean lastMessageDeletedForEveryone;
    private String lastMessageType;
    private String lastMessageCaption;
    private boolean pinned;
    private Instant pinnedAt;
    private boolean muted;
    private Instant mutedUntil;

    public ConversationDto() {}

    public static ConversationDto fromEntity(Conversation conversation) {
        ConversationDto dto = new ConversationDto();
        dto.setId(conversation.getId());
        dto.setType(conversation.getType().name());
        dto.setCreatedAt(conversation.getCreatedAt());
        dto.setUpdatedAt(conversation.getUpdatedAt());
        if (conversation.getMembers() != null) {
            dto.setMembers(conversation.getMembers().stream()
                    .map(ConversationMemberDto::fromEntity)
                    .collect(Collectors.toList()));
        }
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public List<ConversationMemberDto> getMembers() {
        return members;
    }

    public void setMembers(List<ConversationMemberDto> members) {
        this.members = members;
    }

    public Long getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(Long lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public Long getLastMessageSenderUserId() {
        return lastMessageSenderUserId;
    }

    public void setLastMessageSenderUserId(Long lastMessageSenderUserId) {
        this.lastMessageSenderUserId = lastMessageSenderUserId;
    }

    public Instant getLastMessageSentAt() {
        return lastMessageSentAt;
    }

    public void setLastMessageSentAt(Instant lastMessageSentAt) {
        this.lastMessageSentAt = lastMessageSentAt;
    }

    public boolean isLastMessageDeletedForEveryone() {
        return lastMessageDeletedForEveryone;
    }

    public void setLastMessageDeletedForEveryone(boolean lastMessageDeletedForEveryone) {
        this.lastMessageDeletedForEveryone = lastMessageDeletedForEveryone;
    }

    public String getLastMessageType() {
        return lastMessageType;
    }

    public void setLastMessageType(String lastMessageType) {
        this.lastMessageType = lastMessageType;
    }

    public String getLastMessageCaption() {
        return lastMessageCaption;
    }

    public void setLastMessageCaption(String lastMessageCaption) {
        this.lastMessageCaption = lastMessageCaption;
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

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public Instant getMutedUntil() {
        return mutedUntil;
    }

    public void setMutedUntil(Instant mutedUntil) {
        this.mutedUntil = mutedUntil;
    }
}
