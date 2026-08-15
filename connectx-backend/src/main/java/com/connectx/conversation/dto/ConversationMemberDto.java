package com.connectx.conversation.dto;

import com.connectx.conversation.entity.ConversationMember;
import com.connectx.user.dto.UserDto;
import java.time.Instant;

public class ConversationMemberDto {

    private Long id;
    private UserDto user;
    private Instant joinedAt;
    private Long lastReadMessageId;
    private boolean pinned;
    private Instant pinnedAt;

    public ConversationMemberDto() {}

    public static ConversationMemberDto fromEntity(ConversationMember member) {
        ConversationMemberDto dto = new ConversationMemberDto();
        dto.setId(member.getId());
        dto.setUser(UserDto.fromEntity(member.getUser()));
        dto.setJoinedAt(member.getJoinedAt());
        dto.setLastReadMessageId(member.getLastReadMessageId());
        dto.setPinned(member.isPinned());
        dto.setPinnedAt(member.getPinnedAt());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
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
}
