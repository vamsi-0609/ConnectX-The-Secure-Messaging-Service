package com.connectx.message.dto;

import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageType;
import java.time.Instant;

public class MessageDto {

    private Long id;
    private Long conversationId;
    private Long senderUserId;
    private String senderUsername;
    private Long senderDeviceId;
    private Long recipientDeviceId;
    private MessageType messageType;
    private Long mediaId;
    private String caption;
    private Double latitude;
    private Double longitude;
    private String locationLabel;
    private String mimeType;
    private Long fileSizeBytes;
    private String encryptionAlgorithm;
    private String ciphertext;
    private String nonce;
    private Integer groupKeyVersion;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;
    private boolean deletedForEveryone;
    private Long replyToMessageId;
    private String replyToSenderUsername;
    private MessageType replyToMessageType;
    private String replyToCaption;
    private boolean replyToDeleted;
    private java.util.List<MessageReactionDto> reactions = new java.util.ArrayList<>();
    private Instant editedAt;
    private boolean forwarded;
    private Instant pinnedAt;
    private Long pinnedByUserId;
    private String pinnedByUsername;
    private boolean starred;

    // GROUP E2EE media only (Part 9 hardening stage) -- the AES-GCM nonce the FILE bytes
    // themselves were encrypted under (distinct from `nonce` above, which for an IMAGE/DOCUMENT
    // message instead protects the optional encrypted caption). Null for DIRECT media and for
    // every GROUP media message sent before this stage. Not settable from MessageMedia inside
    // fromEntity itself -- Message.mediaId is a plain Long, not a JPA relationship -- so callers
    // that already look up the linked MessageMedia for mimeType/fileSizeBytes set this the same
    // way, right after fromEntity returns.
    private String mediaNonce;

    public MessageDto() {}

    public static MessageDto fromEntity(Message message) {
        return fromEntity(message, null);
    }

    public static MessageDto fromEntity(Message message, String mimeType) {
        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setConversationId(message.getConversation().getId());
        if (message.getSenderUser() != null) {
            dto.setSenderUserId(message.getSenderUser().getId());
            dto.setSenderUsername(message.getSenderUser().getUsername());
        } else if (message.getSenderDevice() != null && message.getSenderDevice().getUser() != null) {
            dto.setSenderUserId(message.getSenderDevice().getUser().getId());
            dto.setSenderUsername(message.getSenderDevice().getUser().getUsername());
        }
        if (message.getSenderDevice() != null) {
            dto.setSenderDeviceId(message.getSenderDevice().getId());
        }
        if (message.getRecipientDevice() != null) {
            dto.setRecipientDeviceId(message.getRecipientDevice().getId());
        }
        dto.setMessageType(message.getMessageType() != null ? message.getMessageType() : MessageType.TEXT);
        dto.setMediaId(message.getMediaId());
        dto.setCaption(message.getCaption());
        dto.setLatitude(message.getLatitude());
        dto.setLongitude(message.getLongitude());
        dto.setLocationLabel(message.getLocationLabel());
        dto.setMimeType(mimeType);
        dto.setEncryptionAlgorithm(message.getEncryptionAlgorithm());
        if (message.isDeletedForEveryone()) {
            dto.setCiphertext("[This message was deleted]");
            dto.setNonce("");
        } else if (dto.getMessageType() == MessageType.LOCATION) {
            // LOCATION never carries a ciphertext -- lat/lng/label are its own plain fields.
            dto.setCiphertext("");
            dto.setNonce("");
        } else if (dto.getMessageType() == MessageType.IMAGE || dto.getMessageType() == MessageType.DOCUMENT) {
            // GROUP media (Part 9 hardening stage): an IMAGE/DOCUMENT message MAY carry an
            // encrypted caption in these same fields (see MessageService#sendMessage) -- must pass
            // through, not be blanked, or the caption ciphertext would never reach the client that
            // needs to decrypt it. Stays "" for DIRECT and for any GROUP media sent with no caption,
            // exactly as before this stage (message.getCiphertext() is "" in both those cases).
            dto.setCiphertext(message.getCiphertext() != null ? message.getCiphertext() : "");
            dto.setNonce(message.getNonce() != null ? message.getNonce() : "");
        } else {
            dto.setCiphertext(message.getCiphertext());
            dto.setNonce(message.getNonce());
        }
        dto.setGroupKeyVersion(message.getGroupKeyVersion());
        dto.setSentAt(message.getSentAt());
        dto.setDeliveredAt(message.getDeliveredAt());
        dto.setReadAt(message.getReadAt());
        dto.setDeletedForEveryone(message.isDeletedForEveryone());

        if (message.getReplyToMessage() != null) {
            Message reply = message.getReplyToMessage();
            dto.setReplyToMessageId(reply.getId());
            if (reply.getSenderUser() != null) {
                dto.setReplyToSenderUsername(reply.getSenderUser().getUsername());
            } else if (reply.getSenderDevice() != null && reply.getSenderDevice().getUser() != null) {
                dto.setReplyToSenderUsername(reply.getSenderDevice().getUser().getUsername());
            }
            dto.setReplyToMessageType(reply.getMessageType());
            // A deleted message's ciphertext is already blanked above (when this DTO IS
            // the deleted message) -- its caption must be blanked the same way when it's
            // only being referenced as someone else's reply target, or "delete for
            // everyone" is defeated for anything that had a caption.
            dto.setReplyToCaption(reply.isDeletedForEveryone() ? null : reply.getCaption());
            dto.setReplyToDeleted(reply.isDeletedForEveryone());
        }

        if (message.getReactions() != null) {
            dto.setReactions(message.getReactions().stream()
                    .map(MessageReactionDto::fromEntity)
                    .collect(java.util.stream.Collectors.toList()));
        }

        dto.setEditedAt(message.getEditedAt());
        dto.setForwarded(message.isForwarded());
        dto.setPinnedAt(message.getPinnedAt());
        if (message.getPinnedBy() != null) {
            dto.setPinnedByUserId(message.getPinnedBy().getId());
            dto.setPinnedByUsername(message.getPinnedBy().getUsername());
        }

        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getSenderUserId() {
        return senderUserId;
    }

    public void setSenderUserId(Long senderUserId) {
        this.senderUserId = senderUserId;
    }

    public String getSenderUsername() {
        return senderUsername;
    }

    public void setSenderUsername(String senderUsername) {
        this.senderUsername = senderUsername;
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

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
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

    public Integer getGroupKeyVersion() {
        return groupKeyVersion;
    }

    public void setGroupKeyVersion(Integer groupKeyVersion) {
        this.groupKeyVersion = groupKeyVersion;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public boolean isDeletedForEveryone() {
        return deletedForEveryone;
    }

    public void setDeletedForEveryone(boolean deletedForEveryone) {
        this.deletedForEveryone = deletedForEveryone;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(Long replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    public String getReplyToSenderUsername() {
        return replyToSenderUsername;
    }

    public void setReplyToSenderUsername(String replyToSenderUsername) {
        this.replyToSenderUsername = replyToSenderUsername;
    }

    public MessageType getReplyToMessageType() {
        return replyToMessageType;
    }

    public void setReplyToMessageType(MessageType replyToMessageType) {
        this.replyToMessageType = replyToMessageType;
    }

    public String getReplyToCaption() {
        return replyToCaption;
    }

    public void setReplyToCaption(String replyToCaption) {
        this.replyToCaption = replyToCaption;
    }

    public boolean isReplyToDeleted() {
        return replyToDeleted;
    }

    public void setReplyToDeleted(boolean replyToDeleted) {
        this.replyToDeleted = replyToDeleted;
    }

    public java.util.List<MessageReactionDto> getReactions() {
        return reactions;
    }

    public void setReactions(java.util.List<MessageReactionDto> reactions) {
        this.reactions = reactions;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public boolean isForwarded() {
        return forwarded;
    }

    public void setForwarded(boolean forwarded) {
        this.forwarded = forwarded;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }

    public void setPinnedAt(Instant pinnedAt) {
        this.pinnedAt = pinnedAt;
    }

    public Long getPinnedByUserId() {
        return pinnedByUserId;
    }

    public void setPinnedByUserId(Long pinnedByUserId) {
        this.pinnedByUserId = pinnedByUserId;
    }

    public String getPinnedByUsername() {
        return pinnedByUsername;
    }

    public void setPinnedByUsername(String pinnedByUsername) {
        this.pinnedByUsername = pinnedByUsername;
    }

    public boolean isStarred() {
        return starred;
    }

    public void setStarred(boolean starred) {
        this.starred = starred;
    }

    public String getMediaNonce() {
        return mediaNonce;
    }

    public void setMediaNonce(String mediaNonce) {
        this.mediaNonce = mediaNonce;
    }
}
