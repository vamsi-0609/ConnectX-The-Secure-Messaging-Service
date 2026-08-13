package com.connectx.message.service;

import com.connectx.common.exception.ApiException;
import com.connectx.common.util.AfterCommitExecutor;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.device.entity.Device;
import com.connectx.device.repository.DeviceRepository;
import com.connectx.media.entity.MessageMedia;
import com.connectx.media.repository.MessageMediaRepository;
import com.connectx.media.service.MediaService;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageType;
import com.connectx.message.entity.MessageUserState;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.repository.MessageUserStateRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.websocket.dto.WsEvent;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final MessageUserStateRepository messageUserStateRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ConversationService conversationService;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final MediaService mediaService;
    private final MessageMediaRepository messageMediaRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    public MessageService(MessageRepository messageRepository,
                          MessageUserStateRepository messageUserStateRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository conversationMemberRepository,
                          ConversationService conversationService,
                          DeviceRepository deviceRepository,
                          UserRepository userRepository,
                          MediaService mediaService,
                          MessageMediaRepository messageMediaRepository,
                          SimpMessagingTemplate messagingTemplate,
                          AfterCommitExecutor afterCommitExecutor) {
        this.messageRepository = messageRepository;
        this.messageUserStateRepository = messageUserStateRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.conversationService = conversationService;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
        this.messageMediaRepository = messageMediaRepository;
        this.messagingTemplate = messagingTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Transactional
    public MessageDto sendMessage(Long currentUserId, SendMessageRequestDto dto) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        Conversation conversation = conversationRepository.findById(dto.getConversationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        ConversationMember senderMembership = conversationMemberRepository.findByConversationIdAndUserId(conversation.getId(), currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "User is not a member of this conversation"));

        MessageType messageType = dto.getMessageType() != null ? dto.getMessageType() : MessageType.TEXT;
        MessageMedia linkedMedia = null;

        if (messageType == MessageType.IMAGE || messageType == MessageType.DOCUMENT) {
            if (dto.getMediaId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_REQUIRED", "Media ID is required for image/document messages");
            }
            linkedMedia = mediaService.getMediaForMessageSend(currentUserId, conversation.getId(), dto.getMediaId());
        } else if (messageType == MessageType.LOCATION) {
            validateLocation(dto.getLatitude(), dto.getLongitude());
        } else {
            if (dto.getCiphertext() == null || dto.getCiphertext().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CIPHERTEXT_REQUIRED", "Ciphertext is required for text messages");
            }
            if (dto.getNonce() == null || dto.getNonce().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NONCE_REQUIRED", "Nonce is required for text messages");
            }
        }

        Device senderDevice = null;
        if (dto.getSenderDeviceId() != null) {
            senderDevice = deviceRepository.findByIdAndUserId(dto.getSenderDeviceId(), currentUserId).orElse(null);
        }
        if (senderDevice == null && messageType == MessageType.TEXT) {
            List<Device> activeDevices = deviceRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(currentUserId);
            if (!activeDevices.isEmpty()) {
                senderDevice = activeDevices.get(0);
            }
        }

        Device recipientDevice = null;
        if (dto.getRecipientDeviceId() != null) {
            recipientDevice = deviceRepository.findById(dto.getRecipientDeviceId()).orElse(null);
            if (recipientDevice == null || !recipientDevice.isActive()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RECIPIENT_DEVICE_NOT_FOUND", "Recipient device is not active");
            }
            boolean recipientIsMember = conversationMemberRepository.findByConversationIdAndUserId(
                    conversation.getId(),
                    recipientDevice.getUser().getId()
            ).isPresent();
            if (!recipientIsMember) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RECIPIENT_DEVICE_NOT_IN_CONVERSATION", "Recipient device does not belong to this conversation");
            }
        }

        String algorithm = dto.getEncryptionAlgorithm() != null && !dto.getEncryptionAlgorithm().isBlank()
                ? dto.getEncryptionAlgorithm()
                : (messageType == MessageType.IMAGE || messageType == MessageType.LOCATION || messageType == MessageType.DOCUMENT ? "NONE" : "ECDH-P256+AES-256-GCM");

        Message message = new Message();
        message.setConversation(conversation);
        message.setSenderUser(currentUser);
        message.setSenderDevice(senderDevice);
        message.setRecipientDevice(recipientDevice);
        message.setMessageType(messageType);
        message.setEncryptionAlgorithm(algorithm);

        if (messageType == MessageType.IMAGE || messageType == MessageType.DOCUMENT) {
            message.setMediaId(linkedMedia.getId());
            message.setCaption(normalizeCaption(dto.getCaption()));
            message.setCiphertext("");
            message.setNonce("");
        } else if (messageType == MessageType.LOCATION) {
            message.setLatitude(dto.getLatitude());
            message.setLongitude(dto.getLongitude());
            message.setLocationLabel(normalizeLocationLabel(dto.getLocationLabel()));
            message.setCiphertext("");
            message.setNonce("");
        } else {
            message.setCiphertext(dto.getCiphertext());
            message.setNonce(dto.getNonce());
        }

        Message savedMessage = messageRepository.save(message);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        Instant messageSentAt = savedMessage.getSentAt();
        List<ConversationMember> allMembers = conversationMemberRepository.findByConversationIdWithUsers(conversation.getId());
        List<String> restoredUsernames = new ArrayList<>();

        if (senderMembership.getDeletedAt() != null) {
            conversationService.forceRestoreConversationForUser(conversation.getId(), currentUserId);
            restoredUsernames.add(currentUser.getUsername());
        }

        for (ConversationMember member : allMembers) {
            if (member.getUser() == null) {
                continue;
            }
            Long memberUserId = member.getUser().getId();
            if (memberUserId.equals(currentUserId)) {
                continue;
            }
            if (conversationService.restoreConversationVisibilityForUser(conversation.getId(), memberUserId, messageSentAt)) {
                restoredUsernames.add(member.getUser().getUsername());
            }
        }

        Map<String, Object> recvPayload = new HashMap<>();
        recvPayload.put("messageId", savedMessage.getId());
        recvPayload.put("conversationId", conversation.getId());
        recvPayload.put("senderUserId", currentUser.getId());
        recvPayload.put("senderUsername", currentUser.getUsername());
        recvPayload.put("senderDeviceId", savedMessage.getSenderDevice() != null ? savedMessage.getSenderDevice().getId() : null);
        recvPayload.put("recipientDeviceId", savedMessage.getRecipientDevice() != null ? savedMessage.getRecipientDevice().getId() : null);
        recvPayload.put("messageType", savedMessage.getMessageType().name());
        recvPayload.put("mediaId", savedMessage.getMediaId());
        recvPayload.put("caption", savedMessage.getCaption());
        recvPayload.put("latitude", savedMessage.getLatitude());
        recvPayload.put("longitude", savedMessage.getLongitude());
        recvPayload.put("locationLabel", savedMessage.getLocationLabel());
        recvPayload.put("mimeType", linkedMedia != null ? linkedMedia.getMimeType() : null);
        recvPayload.put("fileSizeBytes", linkedMedia != null ? linkedMedia.getFileSizeBytes() : null);
        recvPayload.put("encryptionAlgorithm", savedMessage.getEncryptionAlgorithm());
        recvPayload.put("ciphertext", messageType == MessageType.TEXT ? dto.getCiphertext() : "");
        recvPayload.put("nonce", messageType == MessageType.TEXT ? dto.getNonce() : "");
        recvPayload.put("sentAt", savedMessage.getSentAt().toString());

        WsEvent recvEvent = WsEvent.of("MESSAGE_RECEIVED", recvPayload);
        WsEvent restoredEvent = WsEvent.of(
                "CONVERSATION_RESTORED",
                Map.of("conversationId", conversation.getId())
        );

        afterCommitExecutor.runAfterCommit(() -> {
            messagingTemplate.convertAndSend("/topic/conversation/" + conversation.getId(), recvEvent);

            for (ConversationMember member : allMembers) {
                if (member.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(member.getUser().getUsername(), "/queue/messages", recvEvent);
                }
            }

            for (String username : restoredUsernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", restoredEvent);
            }
        });

        String mimeType = linkedMedia != null ? linkedMedia.getMimeType() : null;
        Long fileSizeBytes = linkedMedia != null ? linkedMedia.getFileSizeBytes() : null;
        MessageDto resultDto = MessageDto.fromEntity(savedMessage, mimeType);
        resultDto.setFileSizeBytes(fileSizeBytes);
        return resultDto;
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getConversationMessages(Long currentUserId, Long conversationId) {
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "User is not a member of this conversation");
        }

        Instant clearedAfter = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .map(ConversationMember::getClearedAt)
                .orElse(null);

        List<Message> visibleMessages = messageRepository.findVisibleMessagesForUserInConversation(
                conversationId,
                currentUserId,
                clearedAfter
        );
        return visibleMessages.stream()
                .map(message -> {
                    String mimeType = null;
                    Long fileSizeBytes = null;
                    if ((message.getMessageType() == MessageType.IMAGE || message.getMessageType() == MessageType.DOCUMENT) && message.getMediaId() != null) {
                        MessageMedia mm = messageMediaRepository.findById(message.getMediaId()).orElse(null);
                        if (mm != null) {
                            mimeType = mm.getMimeType();
                            fileSizeBytes = mm.getFileSizeBytes();
                        }
                    }
                    MessageDto resultDto = MessageDto.fromEntity(message, mimeType);
                    resultDto.setFileSizeBytes(fileSizeBytes);
                    return resultDto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteMessage(Long currentUserId, Long messageId, boolean deleteForEveryone) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        Long conversationId = message.getConversation().getId();
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }

        if (deleteForEveryone) {
            Long senderUserId = message.getSenderUser() != null
                    ? message.getSenderUser().getId()
                    : (message.getSenderDevice() != null && message.getSenderDevice().getUser() != null
                            ? message.getSenderDevice().getUser().getId()
                            : null);
            if (senderUserId == null || !senderUserId.equals(currentUserId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the message sender can delete a message for everyone");
            }
            message.setDeletedForEveryone(true);
            message.setDeletedAt(Instant.now());
            messageRepository.save(message);
        } else {
            if (!messageUserStateRepository.existsByMessageIdAndUserId(messageId, currentUserId)) {
                User user = userRepository.findById(currentUserId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
                MessageUserState userState = new MessageUserState(message, user);
                messageUserStateRepository.save(userState);
            }
        }
    }

    @Transactional
    public void markDelivered(Long messageId) {
        messageRepository.findById(messageId).ifPresent(m -> {
            if (m.getDeliveredAt() == null) {
                m.setDeliveredAt(Instant.now());
                messageRepository.save(m);
            }
        });
    }

    @Transactional
    public void markRead(Long messageId) {
        messageRepository.findById(messageId).ifPresent(m -> {
            Instant now = Instant.now();
            if (m.getDeliveredAt() == null) {
                m.setDeliveredAt(now);
            }
            if (m.getReadAt() == null) {
                m.setReadAt(now);
            }
            messageRepository.save(m);
        });
    }

    private String normalizeCaption(String caption) {
        if (caption == null) {
            return null;
        }
        String trimmed = caption.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeLocationLabel(String locationLabel) {
        if (locationLabel == null) {
            return null;
        }
        String trimmed = locationLabel.trim();
        if (trimmed.length() > 500) {
            trimmed = trimmed.substring(0, 500);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOCATION_REQUIRED", "Latitude and longitude are required for location messages");
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "Latitude or longitude is out of range");
        }
    }
}
