package com.connectx.media.entity;

import com.connectx.conversation.entity.Conversation;
import com.connectx.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message_media", indexes = {
    @Index(name = "idx_media_conversation", columnList = "conversation_id")
})
public class MessageMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedBy;

    @Column(name = "storage_key", nullable = false, unique = true, length = 64)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    // E2EE media only (GROUP since Part 9; DIRECT since Phase 6A) -- both null for every plaintext
    // media row (unencrypted DIRECT, or any GROUP row uploaded before Part 9). The AES-GCM nonce
    // used to encrypt the file bytes themselves under the media key -- distinct from any nonce
    // protecting an accompanying caption (that one lives on Message.nonce, reusing the same field
    // TEXT messages already use). Never a plaintext key; the server never sees anything but
    // ciphertext bytes + this nonce.
    @Column(name = "nonce", length = 100)
    private String nonce;

    // GROUP only -- the shared group key version the media bytes were encrypted under, checked
    // against ChatGroup#getKeyVersion at upload time. Always null for DIRECT media (encrypted or
    // not): DIRECT has no shared/versioned key, so this column is meaningless there.
    @Column(name = "group_key_version")
    private Integer groupKeyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public MessageMedia() {}

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

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(User uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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
}
