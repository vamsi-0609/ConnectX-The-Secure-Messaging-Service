package com.connectx.conversation.dto;

import com.connectx.conversation.entity.ConversationMember;
import com.connectx.user.dto.PublicUserDto;
import java.time.Instant;

public class ConversationMemberDto {

    private Long id;
    private PublicUserDto user;
    private Instant joinedAt;
    private Long lastReadMessageId;
    private boolean pinned;
    private Instant pinnedAt;
    private Instant mutedUntil;
    private boolean muted;
    private boolean archived;
    private Instant archivedAt;
    private boolean manuallyMarkedUnread;

    public ConversationMemberDto() {}

    public static ConversationMemberDto fromEntity(ConversationMember member) {
        return fromEntity(member, true);
    }

    // photoVisible is resolved by the caller (ConversationService, via ProfileVisibilityService)
    // since it depends on the viewer, which this DTO layer has no notion of.
    public static ConversationMemberDto fromEntity(ConversationMember member, boolean photoVisible) {
        ConversationMemberDto dto = new ConversationMemberDto();
        dto.setId(member.getId());
        dto.setUser(PublicUserDto.fromEntity(member.getUser(), photoVisible));
        dto.setJoinedAt(member.getJoinedAt());
        dto.setLastReadMessageId(member.getLastReadMessageId());
        dto.setPinned(member.isPinned());
        dto.setPinnedAt(member.getPinnedAt());
        dto.setMutedUntil(member.getMutedUntil());
        dto.setMuted(member.getMutedUntil() != null && member.getMutedUntil().isAfter(Instant.now()));
        dto.setArchived(member.isArchived());
        dto.setArchivedAt(member.getArchivedAt());
        dto.setManuallyMarkedUnread(member.isManuallyMarkedUnread());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PublicUserDto getUser() {
        return user;
    }

    public void setUser(PublicUserDto user) {
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

    public Instant getMutedUntil() {
        return mutedUntil;
    }

    public void setMutedUntil(Instant mutedUntil) {
        this.mutedUntil = mutedUntil;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
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
}
