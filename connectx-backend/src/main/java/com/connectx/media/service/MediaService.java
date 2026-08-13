package com.connectx.media.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
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

    public MediaService(MessageMediaRepository messageMediaRepository,
                        ConversationRepository conversationRepository,
                        ConversationMemberRepository conversationMemberRepository,
                        UserRepository userRepository,
                        MediaStorage mediaStorage) {
        this.messageMediaRepository = messageMediaRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
        this.mediaStorage = mediaStorage;
    }

    @Transactional
    public MediaUploadResponseDto uploadConversationMedia(Long currentUserId, Long conversationId, MultipartFile file) {
        ensureConversationMember(conversationId, currentUserId);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        User uploader = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        String storageKey = UUID.randomUUID().toString().replace("-", "");
        MediaStorage.StoredMediaFile stored = mediaStorage.store(storageKey, file);

        MessageMedia media = new MessageMedia();
        media.setConversation(conversation);
        media.setUploadedBy(uploader);
        media.setStorageKey(stored.storageKey());
        media.setMimeType(stored.mimeType());
        media.setFileSizeBytes(stored.fileSizeBytes());
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

        ensureConversationMember(media.getConversation().getId(), currentUserId);
        return mediaStorage.load(media.getStorageKey(), media.getMimeType());
    }

    @Transactional(readOnly = true)
    public MessageMedia getMediaEntityForUser(Long currentUserId, Long mediaId) {
        MessageMedia media = messageMediaRepository.findById(mediaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Media was not found"));
        ensureConversationMember(media.getConversation().getId(), currentUserId);
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
