package com.connectx.connection.dto;

import com.connectx.connection.entity.ConnectionRequest;
import com.connectx.user.service.ProfileVisibilityService;

import java.time.Instant;

/**
 * Deliberately excludes email and any other field not already public via
 * GET /api/v1/users/search -- a connection request response should not leak more about
 * either party than discovery already does.
 */
public class ConnectionRequestDto {

    private Long id;
    private Long requesterId;
    private String requesterUsername;
    private String requesterDisplayName;
    private String requesterProfileImageUrl;
    private Long recipientId;
    private String recipientUsername;
    private String recipientDisplayName;
    private String recipientProfileImageUrl;
    private String status;
    private Instant createdAt;
    private Instant respondedAt;

    public ConnectionRequestDto() {}

    public static ConnectionRequestDto fromEntity(ConnectionRequest entity) {
        ConnectionRequestDto dto = new ConnectionRequestDto();
        dto.setId(entity.getId());
        dto.setRequesterId(entity.getRequester().getId());
        dto.setRequesterUsername(entity.getRequester().getUsername());
        dto.setRequesterDisplayName(entity.getRequester().getDisplayName());
        dto.setRequesterProfileImageUrl(entity.getRequester().getProfileImageUrl());
        dto.setRecipientId(entity.getRecipient().getId());
        dto.setRecipientUsername(entity.getRecipient().getUsername());
        dto.setRecipientDisplayName(entity.getRecipient().getDisplayName());
        dto.setRecipientProfileImageUrl(entity.getRecipient().getProfileImageUrl());
        dto.setStatus(entity.getStatus().name());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setRespondedAt(entity.getRespondedAt());
        return dto;
    }

    // The requester and recipient aren't connected while a request is only PENDING, so a
    // CONNECTIONS-only photo must stay hidden here just like anywhere else -- only who the
    // *viewer* (whichever of the two current-user is) is allowed to see is exposed.
    public static ConnectionRequestDto fromEntity(ConnectionRequest entity, Long viewerId, ProfileVisibilityService visibilityService) {
        ConnectionRequestDto dto = fromEntity(entity);
        if (!visibilityService.isProfilePhotoVisible(entity.getRequester(), viewerId)) {
            dto.setRequesterProfileImageUrl(null);
        }
        if (!visibilityService.isProfilePhotoVisible(entity.getRecipient(), viewerId)) {
            dto.setRecipientProfileImageUrl(null);
        }
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(Long requesterId) {
        this.requesterId = requesterId;
    }

    public String getRequesterUsername() {
        return requesterUsername;
    }

    public void setRequesterUsername(String requesterUsername) {
        this.requesterUsername = requesterUsername;
    }

    public String getRequesterDisplayName() {
        return requesterDisplayName;
    }

    public void setRequesterDisplayName(String requesterDisplayName) {
        this.requesterDisplayName = requesterDisplayName;
    }

    public String getRequesterProfileImageUrl() {
        return requesterProfileImageUrl;
    }

    public void setRequesterProfileImageUrl(String requesterProfileImageUrl) {
        this.requesterProfileImageUrl = requesterProfileImageUrl;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public String getRecipientUsername() {
        return recipientUsername;
    }

    public void setRecipientUsername(String recipientUsername) {
        this.recipientUsername = recipientUsername;
    }

    public String getRecipientDisplayName() {
        return recipientDisplayName;
    }

    public void setRecipientDisplayName(String recipientDisplayName) {
        this.recipientDisplayName = recipientDisplayName;
    }

    public String getRecipientProfileImageUrl() {
        return recipientProfileImageUrl;
    }

    public void setRecipientProfileImageUrl(String recipientProfileImageUrl) {
        this.recipientProfileImageUrl = recipientProfileImageUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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
