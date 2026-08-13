package com.connectx.conversation.dto;

import jakarta.validation.constraints.NotNull;

public class CreateDirectConversationDto {

    @NotNull(message = "Target userId is required")
    private Long userId;

    public CreateDirectConversationDto() {}

    public CreateDirectConversationDto(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
