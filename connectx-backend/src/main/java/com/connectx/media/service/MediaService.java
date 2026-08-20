package com.connectx.media.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.service.GroupAuthorizationService;
import com.connectx.media.dto.MediaUploadResponseDto;
import com.connectx.media.entity.MessageMedia;
import com.connectx.media.repository.MessageMediaRepository;
import com.connectx.media.storage.MediaStorage;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
public class MediaService {

    private final MessageMediaRepository messageMediaRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;
    private final MediaStorage mediaStorage;
    private final GroupAuthorizationService groupAuthorizationService;

    public MediaService(MessageMediaRepository messageMediaRepository,
                        ConversationRepository conversationRepository,
                        ConversationMemberRepository conversationMemberRepository,
                        UserRepository userRepository,
                        MediaStorage mediaStorage,
                        GroupAuthorizationService groupAuthorizationService) {
        this.messageMediaRepository = messageMediaRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
        this.mediaStorage = mediaStorage;
        this.groupAuthorizationService = groupAuthorizationService;
    }

    /**
     * DIRECT media: unchanged from before this stage -- plaintext, membership-gated via
     * {@link #ensureConversationMember}. GROUP media (Part 9 hardening stage): the uploaded bytes
     * MUST already be ciphertext (encrypted client-side under the group's current shared key,
     * exactly like GROUP TEXT already requires) -- {@code nonce}/{@code groupKeyVersion} both
     * required and the version checked against {@link ChatGroup#getKeyVersion()}, mirroring
     * MessageService#sendMessage's identical GROUP_KEY_VERSION_MISMATCH contract for text. This
     * method never sees, decrypts, or could decrypt the plaintext media -- only the wrapped
     * ciphertext bytes and metadata already safe to know server-side (claimed type, size).
     */
    @Transactional
    public MediaUploadResponseDto uploadConversationMedia(Long currentUserId, Long conversationId, MultipartFile file,
                                                            String nonce, Integer groupKeyVersion, String claimedMimeType) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        User uploader = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        boolean clientSuppliedEncryption = nonce != null && !nonce.isBlank();
        String storageKey = UUID.randomUUID().toString().replace("-", "");
        MediaStorage.StoredMediaFile stored;
        String persistedNonce = null;
        Integer persistedKeyVersion = null;

        if (conversation.getType() == ConversationType.GROUP) {
            ChatGroup chatGroup = groupAuthorizationService.requireCanSendMessage(currentUserId, conversationId);
            if (!clientSuppliedEncryption || groupKeyVersion == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ENCRYPTION_REQUIRED", "Group media must be uploaded encrypted");
            }
            if (!groupKeyVersion.equals(chatGroup.getKeyVersion())) {
                throw new ApiException(HttpStatus.CONFLICT, "GROUP_KEY_VERSION_MISMATCH", "Your group encryption key is out of date");
            }
            if (claimedMimeType == null || claimedMimeType.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Media type is required");
            }
            stored = mediaStorage.storeEncrypted(storageKey, file, claimedMimeType);
            persistedNonce = nonce;
            persistedKeyVersion = groupKeyVersion;
        } else {
            ensureConversationMember(conversationId, currentUserId);
            if (clientSuppliedEncryption) {
                // Defense-in-depth only -- the frontend never takes this path for DIRECT. Rejecting
                // outright rather than silently ignoring avoids ever storing a nonce this method
                // didn't itself validate a key version for.
                throw new ApiException(HttpStatus.BAD_REQUEST, "ENCRYPTION_NOT_SUPPORTED", "Encrypted uploads are only supported for group conversations");
            }
            stored = mediaStorage.store(storageKey, file);
        }

        MessageMedia media = new MessageMedia();
        media.setConversation(conversation);
        media.setUploadedBy(uploader);
        media.setStorageKey(stored.storageKey());
        media.setMimeType(stored.mimeType());
        media.setFileSizeBytes(stored.fileSizeBytes());
        media.setNonce(persistedNonce);
        media.setGroupKeyVersion(persistedKeyVersion);
        if (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()) {
            media.setOriginalFilename(sanitizeFilename(file.getOriginalFilename()));
        }

        MessageMedia saved = messageMediaRepository.save(media);
        return MediaUploadResponseDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Resource getMediaForUser(Long currentUserId, Long mediaId) {
        MessageMedia media = messageMediaRepository.findById(mediaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Media was not found"));

        ensureCanAccessMedia(media.getConversation(), currentUserId);
        return mediaStorage.load(media.getStorageKey(), media.getMimeType());
    }

    @Transactional(readOnly = true)
    public MessageMedia getMediaEntityForUser(Long currentUserId, Long mediaId) {
        MessageMedia media = messageMediaRepository.findById(mediaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Media was not found"));
        ensureCanAccessMedia(media.getConversation(), currentUserId);
        return media;
    }

    @Transactional(readOnly = true)
    public MessageMedia getMediaForMessageSend(Long currentUserId, Long conversationId, Long mediaId) {
        MessageMedia media = messageMediaRepository.findByIdAndConversationId(mediaId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_NOT_FOUND", "Media was not found for this conversation"));

        if (!media.getUploadedBy().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEDIA_FORBIDDEN", "You can only send media you uploaded");
        }

        ensureConversationMember(conversationId, currentUserId);
        return media;
    }

    /**
     * GROUP media (Part 12 hardening): must follow the exact same "currently ACTIVE member only"
     * rule every other group-scoped read already enforces (GroupAuthorizationService#requireActiveMember
     * -- deletedAt IS NULL) rather than the plain, lifecycle-unaware membership-row lookup below --
     * a member who left or was removed keeps their (soft-deleted) ConversationMember row and must
     * not still be able to pull old group media. DIRECT is untouched: same ensureConversationMember
     * call as before this stage, on every path.
     */
    private void ensureCanAccessMedia(Conversation conversation, Long userId) {
        if (conversation.getType() == ConversationType.GROUP) {
            groupAuthorizationService.requireActiveMember(userId, conversation.getId());
        } else {
            ensureConversationMember(conversation.getId(), userId);
        }
    }

    private void ensureConversationMember(Long conversationId, Long userId) {
        boolean isMember = conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId).isPresent();
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "User is not a member of this conversation");
        }
    }

    private String sanitizeFilename(String filename) {
        String baseName = filename.replace("\\", "/");
        int slashIndex = baseName.lastIndexOf('/');
        if (slashIndex >= 0) {
            baseName = baseName.substring(slashIndex + 1);
        }
        if (baseName.length() > 200) {
            baseName = baseName.substring(0, 200);
        }
        return baseName;
    }
}
