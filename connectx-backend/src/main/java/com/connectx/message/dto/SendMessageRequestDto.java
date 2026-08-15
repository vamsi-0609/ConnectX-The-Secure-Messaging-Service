package com.connectx.message.dto;

import com.connectx.message.entity.MessageType;
import jakarta.validation.constraints.NotNull;

public class SendMessageRequestDto {

    @NotNull(message = "Conversation ID is required")
    private Long conversationId;

    private MessageType messageType = MessageType.TEXT;

    private Long mediaId;

    private String caption;

    private Double latitude;

    private Double longitude;

    private String locationLabel;

    private Long senderDeviceId;

    private Long recipientDeviceId;

    private String encryptionAlgorithm;

    private String ciphertext;

    private String nonce;

    private String requestId;

    private Long replyToMessageId;

    public SendMessageRequestDto() {}

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(Long replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public Long getMediaId() {
        return mediaId;
    }

    public void setMediaId(Long mediaId) {
        this.mediaId = mediaId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getLocationLabel() {
        return locationLabel;
    }

    public void setLocationLabel(String locationLabel) {
        this.locationLabel = locationLabel;
    }

    public Long getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(Long senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
    }

    public Long getRecipientDeviceId() {
        return recipientDeviceId;
    }

    public void setRecipientDeviceId(Long recipientDeviceId) {
        this.recipientDeviceId = recipientDeviceId;
    }

    public String getEncryptionAlgorithm() {
        return encryptionAlgorithm;
    }

    public void setEncryptionAlgorithm(String encryptionAlgorithm) {
        this.encryptionAlgorithm = encryptionAlgorithm;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
