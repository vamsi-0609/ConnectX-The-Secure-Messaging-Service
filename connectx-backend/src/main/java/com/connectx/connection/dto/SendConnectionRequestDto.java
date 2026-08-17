package com.connectx.connection.dto;

import jakarta.validation.constraints.NotNull;

public class SendConnectionRequestDto {

    @NotNull(message = "recipientId is required")
    private Long recipientId;

    public SendConnectionRequestDto() {}

    public SendConnectionRequestDto(Long recipientId) {
        this.recipientId = recipientId;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }
}
