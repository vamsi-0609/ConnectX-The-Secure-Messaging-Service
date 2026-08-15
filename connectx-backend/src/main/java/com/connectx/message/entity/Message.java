package com.connectx.message.entity;

import com.connectx.conversation.entity.Conversation;
import com.connectx.device.entity.Device;
import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_conversation", columnList = "conversation_id, sent_at"),
    @Index(name = "idx_msg_conv_id_desc", columnList = "conversation_id, id")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id")
    private User senderUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_device_id")
    private Device senderDevice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_device_id")
    private Device recipientDevice;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType = MessageType.TEXT;

    @Column(name = "media_id")
    private Long mediaId;

    @Lob
    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "location_label", length = 500)
    private String locationLabel;

    @Column(name = "encryption_algorithm", nullable = false, length = 80)
    private String encryptionAlgorithm = "ECDH-P256+AES-256-GCM";

    @Lob
    @Column(name = "ciphertext", columnDefinition = "TEXT")
    private String ciphertext;

    @Column(name = "nonce", length = 100)
    private String nonce;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "deleted_for_everyone", nullable = false)
    private boolean deletedForEveryone = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_message_id")
    private Message replyToMessage;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "forwarded", nullable = false)
    private boolean forwarded = false;

    @Column(name = "pinned_at")
    private Instant pinnedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pinned_by_user_id")
    private User pinnedBy;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<MessageReaction> reactions = new java.util.ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = Instant.now();
        }
    }

    public Message() {}

    public Message(Conversation conversation, User senderUser, Device senderDevice, Device recipientDevice, String encryptionAlgorithm, String ciphertext, String nonce) {
        this.conversation = conversation;
        this.senderUser = senderUser;
        this.senderDevice = senderDevice;
        this.recipientDevice = recipientDevice;
        this.messageType = MessageType.TEXT;
        this.encryptionAlgorithm = encryptionAlgorithm;
        this.ciphertext = ciphertext;
        this.nonce = nonce;
        this.sentAt = Instant.now();
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

    public User getSenderUser() {
        return senderUser;
    }

    public void setSenderUser(User senderUser) {
        this.senderUser = senderUser;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public Device getSenderDevice() {
        return senderDevice;
    }

    public void setSenderDevice(Device senderDevice) {
        this.senderDevice = senderDevice;
    }

    public Device getRecipientDevice() {
        return recipientDevice;
    }

    public void setRecipientDevice(Device recipientDevice) {
        this.recipientDevice = recipientDevice;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Message getReplyToMessage() {
        return replyToMessage;
    }

    public void setReplyToMessage(Message replyToMessage) {
        this.replyToMessage = replyToMessage;
    }

    public java.util.List<MessageReaction> getReactions() {
        return reactions;
    }

    public void setReactions(java.util.List<MessageReaction> reactions) {
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

    public User getPinnedBy() {
        return pinnedBy;
    }

    public void setPinnedBy(User pinnedBy) {
        this.pinnedBy = pinnedBy;
    }
}
