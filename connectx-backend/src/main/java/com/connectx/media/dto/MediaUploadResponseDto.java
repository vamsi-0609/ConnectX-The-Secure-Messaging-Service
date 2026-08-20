package com.connectx.media.dto;

import com.connectx.media.entity.MessageMedia;

public class MediaUploadResponseDto {

    private Long mediaId;
    private Long conversationId;
    private String mimeType;
    private long fileSizeBytes;

    // GROUP E2EE media only -- null for DIRECT (unencrypted, unchanged). Lets the sender's own
    // optimistic render decrypt immediately after upload without a round trip; see
    // MessageMedia#nonce/groupKeyVersion for what these describe.
    private String nonce;
    private Integer groupKeyVersion;

    public MediaUploadResponseDto() {}

    public static MediaUploadResponseDto fromEntity(MessageMedia media) {
        MediaUploadResponseDto dto = new MediaUploadResponseDto();
        dto.setMediaId(media.getId());
        dto.setConversationId(media.getConversation().getId());
        dto.setMimeType(media.getMimeType());
        dto.setFileSizeBytes(media.getFileSizeBytes());
        dto.setNonce(media.getNonce());
        dto.setGroupKeyVersion(media.getGroupKeyVersion());
        return dto;
    }

    public Long getMediaId() {
        return mediaId;
    }

    public void setMediaId(Long mediaId) {
        this.mediaId = mediaId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
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
