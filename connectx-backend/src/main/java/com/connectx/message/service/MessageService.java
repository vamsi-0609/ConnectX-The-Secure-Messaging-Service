package com.connectx.message.service;

import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.common.util.AfterCommitExecutor;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.device.entity.Device;
import com.connectx.device.repository.DeviceRepository;
import com.connectx.group.service.GroupAuthorizationService;
import com.connectx.media.entity.MessageMedia;
import com.connectx.media.repository.MessageMediaRepository;
import com.connectx.media.service.MediaService;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageStar;
import com.connectx.message.entity.MessageType;
import com.connectx.message.entity.MessageUserState;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.repository.MessageStarRepository;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    private static final long EDIT_WINDOW_MINUTES = 15;

    private final MessageRepository messageRepository;
    private final MessageUserStateRepository messageUserStateRepository;
    private final MessageStarRepository messageStarRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ConversationService conversationService;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserConnectionRepository userConnectionRepository;
    private final MediaService mediaService;
    private final MessageMediaRepository messageMediaRepository;
    private final com.connectx.message.repository.MessageReactionRepository messageReactionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AfterCommitExecutor afterCommitExecutor;
    private final com.connectx.push.service.WebPushService webPushService;
    private final GroupAuthorizationService groupAuthorizationService;
    // Self-injected (via @Lazy, the standard Spring pattern for this) so
    // tryInsertReactionInNewTransaction below can be called as a real proxied bean method —
    // required for its own @Transactional(REQUIRES_NEW) to actually take effect, since
    // Spring's AOP proxy is bypassed on a plain `this.` call from inside the same class.
    private final MessageService self;

    public MessageService(MessageRepository messageRepository,
                          MessageUserStateRepository messageUserStateRepository,
                          MessageStarRepository messageStarRepository,
                          ConversationRepository conversationRepository,
                          ConversationMemberRepository conversationMemberRepository,
                          ConversationService conversationService,
                          DeviceRepository deviceRepository,
                          UserRepository userRepository,
                          UserBlockRepository userBlockRepository,
                          UserConnectionRepository userConnectionRepository,
                          MediaService mediaService,
                          MessageMediaRepository messageMediaRepository,
                          com.connectx.message.repository.MessageReactionRepository messageReactionRepository,
                          SimpMessagingTemplate messagingTemplate,
                          AfterCommitExecutor afterCommitExecutor,
                          com.connectx.push.service.WebPushService webPushService,
                          GroupAuthorizationService groupAuthorizationService,
                          @org.springframework.context.annotation.Lazy MessageService self) {
        this.messageRepository = messageRepository;
        this.messageUserStateRepository = messageUserStateRepository;
        this.messageStarRepository = messageStarRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.conversationService = conversationService;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.userConnectionRepository = userConnectionRepository;
        this.mediaService = mediaService;
        this.messageMediaRepository = messageMediaRepository;
        this.messageReactionRepository = messageReactionRepository;
        this.messagingTemplate = messagingTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
        this.webPushService = webPushService;
        this.groupAuthorizationService = groupAuthorizationService;
        this.self = self;
    }

    @Transactional
    public MessageDto sendMessage(Long currentUserId, SendMessageRequestDto dto) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        Conversation conversation = conversationRepository.findById(dto.getConversationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        ConversationMember senderMembership = conversationMemberRepository.findByConversationIdAndUserId(conversation.getId(), currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "User is not a member of this conversation"));

        // Block and connection enforcement are scoped to DIRECT conversations only (per
        // architecture: a group send is authorized by membership, not by every pairwise
        // relationship among members -- checking all C(N,2) pairs would be both expensive and
        // outside the actual threat model; group authorization is a separate, not-yet-built
        // concern). A DIRECT conversation always has exactly one other member; existing
        // conversation history and the conversation itself are never touched by either check --
        // only a NEW send is rejected, so blocking/disconnecting never deletes or hides what
        // already exists.
        //
        // Conversation membership alone is NOT messaging authorization: a DIRECT conversation
        // can predate the connection system (grandfathered) or outlive a connection that was
        // later removed (e.g. via blocking -- see BlockService#terminateExistingConnectionAnd
        // PendingRequest). Without this UserConnection check, membership plus "not currently
        // blocked" was sufficient to send into any conversation the user still belonged to, even
        // with zero current relationship -- the actual bug this check closes.
        com.connectx.group.entity.ChatGroup groupForSend = null;
        if (conversation.getType() == ConversationType.DIRECT) {
            for (Long otherMemberId : conversationMemberRepository.findMemberUserIdsExcluding(conversation.getId(), currentUserId)) {
                if (userBlockRepository.existsEitherDirection(currentUserId, otherMemberId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "You cannot send messages to this user");
                }
                Long low = Math.min(currentUserId, otherMemberId);
                Long high = Math.max(currentUserId, otherMemberId);
                if (!userConnectionRepository.existsByUserLowIdAndUserHighId(low, high)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONNECTED",
                            "You must be connected with this user to send messages");
                }
            }
        } else if (conversation.getType() == ConversationType.GROUP) {
            // Groups Stage 5: authorization is membership + role-based, never pairwise
            // block/connection checks against every other member -- a GROUP member does not need
            // to be connected to (or unblocked by) every other member to send, per
            // docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md's Stage 5 checkpoint and
            // CONNECTX_GROUP_ARCHITECTURE.md's approved blocking semantics (a block never removes
            // shared group membership or hides existing group messages). requireCanSendMessage
            // covers: group exists and is actually type GROUP, sender has ACTIVE membership
            // (deletedAt IS NULL -- stricter than the plain membership-row lookup above, which a
            // few lines below is used for DIRECT's separate "soft-deleted sender restores their
            // own hidden view" behavior; that semantic does not apply to a GROUP member who left
            // or was removed, so this check must reject them before that logic is ever reached),
            // and the group's current who_can_send_messages policy. GroupAuthorizationService is
            // the sole authority for this decision, exactly as every prior Groups stage's
            // convention -- MessageService does not re-derive any of it.
            groupForSend = groupAuthorizationService.requireCanSendMessage(currentUserId, conversation.getId());
        }

        MessageType messageType = dto.getMessageType() != null ? dto.getMessageType() : MessageType.TEXT;
        MessageMedia linkedMedia = null;

        // GROUP media (Part 9 hardening stage): the caption is optional, but when a GROUP sender
        // supplies one it must already be encrypted client-side under the group's current shared
        // key -- exactly the same ciphertext/nonce/groupKeyVersion contract TEXT already enforces
        // below, just conditional on a caption actually being present (an image with no caption has
        // nothing here to encrypt or validate). The media FILE's own encryption was already
        // validated separately at upload time (MediaService#uploadConversationMedia); this only
        // covers the caption riding alongside it in this same Message row.
        //
        // Phase 6A: DIRECT senders may now supply the same ciphertext/nonce pair -- eventually the
        // ECDH-wrapped {mediaKey + metadata} payload, not a plaintext caption -- reusing this exact
        // column pair. DIRECT has no shared/versioned group key, so groupKeyVersion is validated
        // (and persisted) only when groupForSend != null; it must otherwise stay unpersisted, per
        // MediaService's identical DIRECT contract.
        boolean hasEncryptedCaption = dto.getCiphertext() != null && !dto.getCiphertext().isBlank();

        if (messageType == MessageType.IMAGE || messageType == MessageType.DOCUMENT) {
            if (dto.getMediaId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_REQUIRED", "Media ID is required for image/document messages");
            }
            linkedMedia = mediaService.getMediaForMessageSend(currentUserId, conversation.getId(), dto.getMediaId());

            if (hasEncryptedCaption) {
                if (dto.getNonce() == null || dto.getNonce().isBlank()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "NONCE_REQUIRED", "Nonce is required when a caption ciphertext is provided");
                }
                if (groupForSend != null
                        && (dto.getGroupKeyVersion() == null || dto.getGroupKeyVersion() != groupForSend.getKeyVersion())) {
                    throw new ApiException(HttpStatus.CONFLICT, "GROUP_KEY_VERSION_MISMATCH",
                            "Your group encryption key is out of date");
                }
            } else if (groupForSend != null && dto.getCaption() != null && !dto.getCaption().isBlank()) {
                // A GROUP sender's client must never fall back to a plaintext caption -- this
                // would silently defeat the encryption Part 9 exists to guarantee. Reject rather
                // than silently store it, so a client-side bug fails loudly instead of leaking.
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAINTEXT_CAPTION_NOT_ALLOWED",
                        "Group image/document captions must be encrypted");
            }
        } else if (messageType == MessageType.LOCATION) {
            validateLocation(dto.getLatitude(), dto.getLongitude());
        } else {
            if (dto.getCiphertext() == null || dto.getCiphertext().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CIPHERTEXT_REQUIRED", "Ciphertext is required for text messages");
            }
            if (dto.getNonce() == null || dto.getNonce().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NONCE_REQUIRED", "Nonce is required for text messages");
            }
            // GROUP E2EE: this ciphertext must have been encrypted under the group's CURRENT
            // shared key. Rejecting any other version outright (never silently rewritten to the
            // current version) is what makes a removed/left member's stale locally-cached key
            // actually useless for sending, and what forces a client that hasn't caught up with a
            // rotation yet to refresh before it can send -- see docs on the group key lifecycle.
            if (groupForSend != null) {
                if (dto.getGroupKeyVersion() == null || dto.getGroupKeyVersion() != groupForSend.getKeyVersion()) {
                    throw new ApiException(HttpStatus.CONFLICT, "GROUP_KEY_VERSION_MISMATCH",
                            "Your group encryption key is out of date");
                }
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

        Message replyToMessage = null;
        if (dto.getReplyToMessageId() != null) {
            replyToMessage = messageRepository.findById(dto.getReplyToMessageId()).orElse(null);
            if (replyToMessage != null && !replyToMessage.getConversation().getId().equals(conversation.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CROSS_CONVERSATION_REPLY_NOT_ALLOWED", "Cannot reply to a message from a different conversation");
            }
            if (replyToMessage != null && replyToMessage.isDeletedForEveryone()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REPLY_TARGET_DELETED", "Cannot reply to a message that was deleted");
            }
        }

        Message message = new Message();
        message.setConversation(conversation);
        message.setSenderUser(currentUser);
        message.setSenderDevice(senderDevice);
        message.setRecipientDevice(recipientDevice);
        message.setMessageType(messageType);
        message.setEncryptionAlgorithm(algorithm);
        message.setReplyToMessage(replyToMessage);
        message.setForwarded(dto.isForwarded());

        if (messageType == MessageType.IMAGE || messageType == MessageType.DOCUMENT) {
            message.setMediaId(linkedMedia.getId());
            if (hasEncryptedCaption) {
                // Encrypted caption/metadata (GROUP or, as of Phase 6A, DIRECT) -- reuses the same
                // Message.ciphertext/nonce columns TEXT already uses, never Message.caption (which
                // stays unset/null here, exactly like a plaintext caption never gets a ciphertext).
                // groupKeyVersion is GROUP-only -- stays unpersisted (null) for DIRECT.
                message.setCiphertext(dto.getCiphertext());
                message.setNonce(dto.getNonce());
                if (groupForSend != null) {
                    message.setGroupKeyVersion(dto.getGroupKeyVersion());
                }
            } else {
                message.setCaption(normalizeCaption(dto.getCaption()));
                message.setCiphertext("");
                message.setNonce("");
            }
        } else if (messageType == MessageType.LOCATION) {
            message.setLatitude(dto.getLatitude());
            message.setLongitude(dto.getLongitude());
            message.setLocationLabel(normalizeLocationLabel(dto.getLocationLabel()));
            message.setCiphertext("");
            message.setNonce("");
        } else {
            message.setCiphertext(dto.getCiphertext());
            message.setNonce(dto.getNonce());
            if (groupForSend != null) {
                message.setGroupKeyVersion(dto.getGroupKeyVersion());
            }
        }

        Message savedMessage = messageRepository.save(message);

        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);

        Instant messageSentAt = savedMessage.getSentAt();
        List<ConversationMember> allMembers = conversationMemberRepository.findByConversationIdWithUsers(conversation.getId());
        List<String> restoredUsernames = new ArrayList<>();

        // "Restore visibility on new activity" is a DIRECT-only concept: deletedAt there means
        // "I hid this chat for myself" (see ConversationService#restoreConversationVisibilityForUser's
        // own javadoc), so surfacing it again when a new message arrives is correct DIRECT UX. For
        // GROUP, deletedAt means the member LEFT or was REMOVED (Stage 1.5's consent rule) -- a
        // brand-new message must never silently reactivate that membership. requireCanSendMessage
        // above already guarantees the SENDER is active for a GROUP send, so the self-restore
        // branch is structurally unreachable there anyway; the loop below iterates every OTHER
        // member too, including any who left/were removed, so it must not run for GROUP at all or
        // it would resurrect their membership the moment anyone else sends a message -- exactly
        // the bug this Stage 5 gate exists to prevent.
        if (conversation.getType() == ConversationType.DIRECT) {
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
                // Skip the restore-visibility round trip entirely for the common case (member
                // hasn't deleted this conversation) -- restoreConversationVisibilityForUser
                // would only re-fetch this same row and immediately no-op anyway.
                if (member.getDeletedAt() != null
                        && conversationService.restoreConversationVisibilityForUser(conversation.getId(), memberUserId, messageSentAt)) {
                    restoredUsernames.add(member.getUser().getUsername());
                }
            }
        }

        // Who actually receives the live broadcast/push below. DIRECT keeps using the unfiltered
        // allMembers (a soft-deleted DIRECT member was just restored above, so including them is
        // correct -- they should see the conversation "reappear" with this very message). GROUP
        // must exclude anyone with deletedAt set: a member who left or was removed must not
        // receive newly broadcast group messages, on their personal /queue/messages channel or as
        // a push notification, even though the restore step above never ran for them.
        List<ConversationMember> broadcastRecipients = conversation.getType() == ConversationType.GROUP
                ? allMembers.stream().filter(m -> m.getDeletedAt() == null).collect(Collectors.toList())
                : allMembers;

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
        recvPayload.put("mediaNonce", linkedMedia != null ? linkedMedia.getNonce() : null);
        recvPayload.put("encryptionAlgorithm", savedMessage.getEncryptionAlgorithm());
        // savedMessage's own ciphertext/nonce (not re-derived from dto) -- correct for TEXT, for a
        // GROUP encrypted caption on IMAGE/DOCUMENT, and stays "" for DIRECT/no-caption media,
        // exactly matching what was just persisted above.
        recvPayload.put("ciphertext", savedMessage.getCiphertext());
        recvPayload.put("nonce", savedMessage.getNonce());
        recvPayload.put("groupKeyVersion", savedMessage.getGroupKeyVersion());
        recvPayload.put("sentAt", savedMessage.getSentAt().toString());
        recvPayload.put("clientTempId", dto.getRequestId());
        recvPayload.put("forwarded", savedMessage.isForwarded());

        if (savedMessage.getReplyToMessage() != null) {
            Message reply = savedMessage.getReplyToMessage();
            recvPayload.put("replyToMessageId", reply.getId());
            if (reply.getSenderUser() != null) {
                recvPayload.put("replyToSenderUsername", reply.getSenderUser().getUsername());
            } else if (reply.getSenderDevice() != null && reply.getSenderDevice().getUser() != null) {
                recvPayload.put("replyToSenderUsername", reply.getSenderDevice().getUser().getUsername());
            }
            recvPayload.put("replyToMessageType", reply.getMessageType() != null ? reply.getMessageType().name() : "TEXT");
            // Same guard as MessageDto.fromEntity — never surface a deleted reply target's caption.
            recvPayload.put("replyToCaption", reply.isDeletedForEveryone() ? null : reply.getCaption());
            recvPayload.put("replyToDeleted", reply.isDeletedForEveryone());
        }

        WsEvent recvEvent = WsEvent.of("MESSAGE_RECEIVED", recvPayload);
        WsEvent restoredEvent = WsEvent.of(
                "CONVERSATION_RESTORED",
                Map.of("conversationId", conversation.getId())
        );

        boolean isGroupSend = conversation.getType() == ConversationType.GROUP;
        afterCommitExecutor.runAfterCommit(() -> {
            // GROUP conversations skip the shared /topic/conversation/{id} broadcast entirely --
            // Spring's STOMP broker only re-checks subscribe-time authorization
            // (WebSocketAuthChannelInterceptor), never per delivered message, so a member who was
            // just removed/left but still has that topic open in an already-connected browser tab
            // would otherwise keep receiving every new group message regardless. The per-member
            // convertAndSendToUser loop below already correctly excludes them (broadcastRecipients
            // is pre-filtered to currently-ACTIVE members) and is sufficient on its own for
            // delivery to every legitimate recipient -- DIRECT keeps using the topic too since a
            // DIRECT conversation's only two members never have this exposure (removing/leaving
            // isn't a DIRECT concept).
            if (!isGroupSend) {
                messagingTemplate.convertAndSend("/topic/conversation/" + conversation.getId(), recvEvent);
            }

            for (ConversationMember member : broadcastRecipients) {
                if (member.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(member.getUser().getUsername(), "/queue/messages", recvEvent);

                    if (!member.getUser().getId().equals(currentUserId)) {
                        boolean isMuted = member.getMutedUntil() != null && member.getMutedUntil().isAfter(Instant.now());
                        if (!isMuted) {
                            String pushTitle = currentUser.getDisplayName() != null && !currentUser.getDisplayName().isBlank()
                                    ? currentUser.getDisplayName()
                                    : currentUser.getUsername();
                            // TEXT is never previewed here: its "ciphertext" field (see above) is
                            // genuinely E2EE and this method never decrypts it -- doing so just to
                            // populate a push preview would mean the server routinely decrypting
                            // "end-to-end encrypted" messages, which defeats the point regardless
                            // of the push transport itself being encrypted. DOCUMENT similarly
                            // never forwards its caption, even though captions aren't E2EE today
                            // (encryptionAlgorithm "NONE"), to avoid handing the push provider any
                            // more than the minimum needed to render a notification.
                            String pushBody = switch (savedMessage.getMessageType()) {
                                case IMAGE -> "Photo";
                                case LOCATION -> "Location";
                                case DOCUMENT -> "Document";
                                case TEXT -> "New message";
                            };
                            webPushService.sendPushToUserAsync(member.getUser().getId(), pushTitle, pushBody,
                                    conversation.getId(), currentUser.getProfileImageUrl());
                        }
                    }
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
        resultDto.setMediaNonce(linkedMedia != null ? linkedMedia.getNonce() : null);
        return resultDto;
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getConversationMessages(Long currentUserId, Long conversationId) {
        return getConversationMessagesPaged(currentUserId, conversationId, null, 100).getMessages();
    }

    @Transactional(readOnly = true)
    public com.connectx.message.dto.PagedMessageResponseDto getConversationMessagesPaged(
            Long currentUserId,
            Long conversationId,
            Long beforeId,
            int limit) {
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "User is not a member of this conversation");
        }

        int pageSize = Math.max(1, Math.min(limit, 100));

        Instant clearedAfter = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .map(ConversationMember::getClearedAt)
                .orElse(null);

        List<Message> visibleMessages = messageRepository.findVisibleMessagesPaged(
                conversationId,
                currentUserId,
                beforeId,
                clearedAfter,
                org.springframework.data.domain.PageRequest.of(0, pageSize + 1)
        );

        boolean hasMore = visibleMessages.size() > pageSize;
        if (hasMore) {
            visibleMessages = new ArrayList<>(visibleMessages.subList(0, pageSize));
        } else {
            visibleMessages = new ArrayList<>(visibleMessages);
        }

        Long nextCursor = visibleMessages.isEmpty() ? null : visibleMessages.get(visibleMessages.size() - 1).getId();

        // Batch fetch reactions
        List<Long> messageIds = visibleMessages.stream().map(Message::getId).collect(Collectors.toList());
        Map<Long, List<com.connectx.message.dto.MessageReactionDto>> reactionsMap = new HashMap<>();
        if (!messageIds.isEmpty()) {
            List<com.connectx.message.entity.MessageReaction> reactions = messageReactionRepository.findByMessageIdInWithUsers(messageIds);
            reactionsMap = reactions.stream()
                    .map(com.connectx.message.dto.MessageReactionDto::fromEntity)
                    .collect(Collectors.groupingBy(com.connectx.message.dto.MessageReactionDto::getMessageId));
        }

        // Batch fetch media metadata to eliminate N+1 queries
        List<Long> mediaIds = visibleMessages.stream()
                .filter(m -> (m.getMessageType() == MessageType.IMAGE || m.getMessageType() == MessageType.DOCUMENT) && m.getMediaId() != null)
                .map(Message::getMediaId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, MessageMedia> mediaMap = new HashMap<>();
        if (!mediaIds.isEmpty()) {
            List<MessageMedia> mediaList = messageMediaRepository.findAllById(mediaIds);
            mediaList.forEach(mm -> mediaMap.put(mm.getId(), mm));
        }

        final Map<Long, List<com.connectx.message.dto.MessageReactionDto>> finalReactionsMap = reactionsMap;

        // Batch fetch which of these messages the current user has starred
        final java.util.Set<Long> starredIds = messageIds.isEmpty()
                ? java.util.Set.of()
                : new java.util.HashSet<>(messageStarRepository.findStarredMessageIds(currentUserId, messageIds));

        // Reverse to chronological order (ASC)
        java.util.Collections.reverse(visibleMessages);

        List<MessageDto> dtos = visibleMessages.stream()
                .map(message -> {
                    String mimeType = null;
                    Long fileSizeBytes = null;
                    String mediaNonce = null;
                    if ((message.getMessageType() == MessageType.IMAGE || message.getMessageType() == MessageType.DOCUMENT) && message.getMediaId() != null) {
                        MessageMedia mm = mediaMap.get(message.getMediaId());
                        if (mm != null) {
                            mimeType = mm.getMimeType();
                            fileSizeBytes = mm.getFileSizeBytes();
                            mediaNonce = mm.getNonce();
                        }
                    }
                    MessageDto resultDto = MessageDto.fromEntity(message, mimeType);
                    resultDto.setFileSizeBytes(fileSizeBytes);
                    resultDto.setMediaNonce(mediaNonce);
                    resultDto.setReactions(finalReactionsMap.getOrDefault(message.getId(), List.of()));
                    resultDto.setStarred(starredIds.contains(message.getId()));
                    return resultDto;
                })
                .collect(Collectors.toList());

        return new com.connectx.message.dto.PagedMessageResponseDto(dtos, hasMore, nextCursor, pageSize);
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
            boolean wasPinned = message.getPinnedAt() != null;
            message.setPinnedAt(null);
            message.setPinnedBy(null);
            messageRepository.save(message);

            Map<String, Object> payload = new HashMap<>();
            payload.put("messageId", messageId);
            payload.put("conversationId", conversationId);
            WsEvent deletedEvent = WsEvent.of("MESSAGE_DELETED", payload);

            Map<String, Object> unpinPayload = wasPinned ? new HashMap<>(Map.of("messageId", messageId, "conversationId", conversationId)) : null;
            WsEvent unpinnedEvent = unpinPayload != null ? WsEvent.of("MESSAGE_UNPINNED", unpinPayload) : null;

            // See sendMessage's isGroupSend comment: GROUP conversations never broadcast to the
            // shared topic, only to each currently-ACTIVE member's personal queue.
            boolean isGroupConv = message.getConversation().getType() == ConversationType.GROUP;
            afterCommitExecutor.runAfterCommit(() -> {
                if (!isGroupConv) {
                    messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, deletedEvent);
                }
                List<ConversationMember> members = conversationMemberRepository.findByConversationIdAndDeletedAtIsNullWithUsers(conversationId);
                for (ConversationMember m : members) {
                    if (m.getUser() != null) {
                        messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", deletedEvent);
                        if (unpinnedEvent != null) {
                            messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", unpinnedEvent);
                        }
                    }
                }
                if (unpinnedEvent != null && !isGroupConv) {
                    messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, unpinnedEvent);
                }
            });
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
    public MessageDto editMessage(Long currentUserId, Long messageId, String newCiphertext, String newNonce) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        Long conversationId = message.getConversation().getId();
        Long senderUserId = message.getSenderUser() != null ? message.getSenderUser().getId() : null;
        if (senderUserId == null || !senderUserId.equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the sender can edit this message");
        }
        if (message.getMessageType() != MessageType.TEXT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_EDITABLE", "Only text messages can be edited");
        }
        if (message.isDeletedForEveryone()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_EDITABLE", "Deleted messages cannot be edited");
        }
        if (message.getSentAt().isBefore(Instant.now().minus(EDIT_WINDOW_MINUTES, java.time.temporal.ChronoUnit.MINUTES))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EDIT_WINDOW_EXPIRED", "This message can no longer be edited");
        }
        if (newCiphertext == null || newCiphertext.isBlank() || newNonce == null || newNonce.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CIPHERTEXT_REQUIRED", "Ciphertext and nonce are required");
        }

        message.setCiphertext(newCiphertext);
        message.setNonce(newNonce);
        Instant editedAt = Instant.now();
        message.setEditedAt(editedAt);
        Message saved = messageRepository.save(message);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", conversationId);
        payload.put("ciphertext", newCiphertext);
        payload.put("nonce", newNonce);
        payload.put("editedAt", editedAt.toString());
        WsEvent editedEvent = WsEvent.of("MESSAGE_EDITED", payload);

        boolean isGroupConv = message.getConversation().getType() == ConversationType.GROUP;
        afterCommitExecutor.runAfterCommit(() -> {
            if (!isGroupConv) {
                messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, editedEvent);
            }
            List<ConversationMember> members = conversationMemberRepository.findByConversationIdAndDeletedAtIsNullWithUsers(conversationId);
            for (ConversationMember m : members) {
                if (m.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", editedEvent);
                }
            }
        });

        return MessageDto.fromEntity(saved);
    }

    @Transactional
    public MessageDto pinMessage(Long currentUserId, Long messageId) {
        return setPinned(currentUserId, messageId, true);
    }

    @Transactional
    public MessageDto unpinMessage(Long currentUserId, Long messageId) {
        return setPinned(currentUserId, messageId, false);
    }

    private MessageDto setPinned(Long currentUserId, Long messageId, boolean pinned) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        Long conversationId = message.getConversation().getId();
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }
        if (pinned && message.isDeletedForEveryone()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PINNABLE", "Deleted messages cannot be pinned");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        Instant pinnedAt = pinned ? Instant.now() : null;
        message.setPinnedAt(pinnedAt);
        message.setPinnedBy(pinned ? currentUser : null);
        Message saved = messageRepository.save(message);

        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", conversationId);
        payload.put("pinnedAt", pinnedAt != null ? pinnedAt.toString() : null);
        payload.put("pinnedByUserId", pinned ? currentUserId : null);
        payload.put("pinnedByUsername", pinned ? currentUser.getUsername() : null);
        WsEvent pinEvent = WsEvent.of(pinned ? "MESSAGE_PINNED" : "MESSAGE_UNPINNED", payload);

        boolean isGroupConv = message.getConversation().getType() == ConversationType.GROUP;
        afterCommitExecutor.runAfterCommit(() -> {
            if (!isGroupConv) {
                messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, pinEvent);
            }
            List<ConversationMember> members = conversationMemberRepository.findByConversationIdAndDeletedAtIsNullWithUsers(conversationId);
            for (ConversationMember m : members) {
                if (m.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", pinEvent);
                }
            }
        });

        return MessageDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public MessageDto getPinnedMessage(Long currentUserId, Long conversationId) {
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }
        return messageRepository.findTopByConversationIdAndPinnedAtIsNotNullAndDeletedForEveryoneFalseOrderByPinnedAtDesc(conversationId)
                .map(MessageDto::fromEntity)
                .orElse(null);
    }

    @Transactional
    public void starMessage(Long currentUserId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                message.getConversation().getId(), currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }
        if (!messageStarRepository.existsByMessageIdAndUserId(messageId, currentUserId)) {
            try {
                self.insertStarInNewTransaction(messageId, currentUserId);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Lost a race with a concurrent star insert for the same (message, user) pair --
                // same isolated-REQUIRES_NEW pattern as insertReactionInNewTransaction below.
                // The winner's row is already committed, so the star already exists; nothing
                // more to do (unlike reactions, a star has no value to reconcile).
                log.debug("Duplicate star insert lost race, already starred: messageId={}, userId={}", messageId, currentUserId);
            }
        }
    }

    /**
     * Attempts the "first star from this user on this message" insert in its own, independent
     * transaction (REQUIRES_NEW) -- see {@link #insertReactionInNewTransaction} for why this
     * needs its own transaction and must be invoked through {@code self}, not {@code this}.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void insertStarInNewTransaction(Long messageId, Long userId) {
        Message messageRef = messageRepository.getReferenceById(messageId);
        User userRef = userRepository.getReferenceById(userId);
        messageStarRepository.saveAndFlush(new MessageStar(messageRef, userRef));
    }

    @Transactional
    public void unstarMessage(Long currentUserId, Long messageId) {
        messageStarRepository.findByMessageIdAndUserId(messageId, currentUserId)
                .ifPresent(messageStarRepository::delete);
    }

    @Transactional
    public void markDelivered(Long messageId, Long currentUserId) {
        messageRepository.findById(messageId).ifPresent(m -> {
            boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                    m.getConversation().getId(), currentUserId);
            if (!isMember) {
                return;
            }
            if (m.getDeliveredAt() == null) {
                Instant now = Instant.now();
                m.setDeliveredAt(now);
                messageRepository.save(m);

                if (m.getSenderUser() != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("messageId", m.getId());
                    payload.put("conversationId", m.getConversation().getId());
                    payload.put("deliveredAt", now.toString());
                    payload.put("readAt", m.getReadAt() != null ? m.getReadAt().toString() : null);

                    WsEvent event = WsEvent.of("READ_RECEIPT_UPDATE", payload);
                    afterCommitExecutor.runAfterCommit(() -> {
                        messagingTemplate.convertAndSendToUser(m.getSenderUser().getUsername(), "/queue/messages", event);
                    });
                }
            }
        });
    }

    @Transactional
    public void markRead(Long messageId, Long currentUserId) {
        messageRepository.findById(messageId).ifPresent(m -> {
            boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(
                    m.getConversation().getId(), currentUserId);
            if (!isMember) {
                return;
            }
            Instant now = Instant.now();
            boolean updated = false;
            if (m.getDeliveredAt() == null) {
                m.setDeliveredAt(now);
                updated = true;
            }
            if (m.getReadAt() == null) {
                m.setReadAt(now);
                updated = true;
            }
            if (updated) {
                messageRepository.save(m);

                if (m.getSenderUser() != null) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("messageId", m.getId());
                    payload.put("conversationId", m.getConversation().getId());
                    payload.put("deliveredAt", m.getDeliveredAt().toString());
                    payload.put("readAt", m.getReadAt().toString());

                    WsEvent event = WsEvent.of("READ_RECEIPT_UPDATE", payload);
                    afterCommitExecutor.runAfterCommit(() -> {
                        messagingTemplate.convertAndSendToUser(m.getSenderUser().getUsername(), "/queue/messages", event);
                    });
                }
            }
        });
    }

    @Transactional
    public void markConversationAsRead(Long conversationId, Long currentUserId) {
        markConversationAsRead(conversationId, currentUserId, null);
    }

    @Transactional
    public void markConversationAsRead(Long conversationId, Long currentUserId, Long maxMessageId) {
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            return;
        }

        // Opening/reading the conversation clears any manual "mark as unread" override,
        // regardless of whether there happen to be new messages from others to mark read.
        conversationService.clearManuallyMarkedUnreadIfSet(conversationId, currentUserId);

        List<Message> unreadMessages = messageRepository.findUnreadMessagesFromOthersInConversationUpTo(
                conversationId, currentUserId, maxMessageId);
        if (unreadMessages.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        messageRepository.bulkMarkReadInConversation(conversationId, currentUserId, maxMessageId, now);

        Map<String, List<Long>> senderMessageIds = new HashMap<>();
        for (Message m : unreadMessages) {
            if (m.getSenderUser() != null) {
                senderMessageIds.computeIfAbsent(m.getSenderUser().getUsername(), k -> new ArrayList<>()).add(m.getId());
            }
        }

        if (!senderMessageIds.isEmpty()) {
            afterCommitExecutor.runAfterCommit(() -> {
                senderMessageIds.forEach((senderUsername, msgIds) -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("conversationId", conversationId);
                    payload.put("messageIds", msgIds);
                    if (msgIds.size() == 1) {
                        payload.put("messageId", msgIds.get(0));
                    }
                    payload.put("deliveredAt", now.toString());
                    payload.put("readAt", now.toString());

                    WsEvent event = WsEvent.of("READ_RECEIPT_UPDATE", payload);
                    messagingTemplate.convertAndSendToUser(senderUsername, "/queue/messages", event);
                });
            });
        }
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

    /**
     * Attempts the "first reaction from this user on this message" insert in its own,
     * independent transaction (REQUIRES_NEW). A concurrent duplicate insert hits the
     * {@code uk_message_user_reaction} unique constraint — running this in a separate
     * transaction means that failure is fully isolated to this small transaction, never
     * the caller's.
     * <p>
     * Deliberately does NOT catch {@code DataIntegrityViolationException} itself: once
     * {@code saveAndFlush} fails, Hibernate marks the underlying session rollback-only
     * regardless of whether the exception is caught in Java, so returning normally from
     * this method (even to report "false") would make Spring try to commit an
     * already-broken transaction and throw {@code UnexpectedRollbackException} instead
     * (confirmed empirically — see ReactionRaceIntegrationTest). Letting the exception
     * propagate lets Spring roll back cleanly, and the caller catches it from the
     * (unaffected, separate) outer transaction instead.
     * <p>
     * Must be called through {@code self} (the injected proxy), not {@code this} --
     * calling it directly from within the class would bypass Spring's transactional proxy
     * and this REQUIRES_NEW annotation would silently have no effect.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void insertReactionInNewTransaction(Long messageId, Long userId, String cleanReaction) {
        Message messageRef = messageRepository.getReferenceById(messageId);
        User userRef = userRepository.getReferenceById(userId);
        messageReactionRepository.saveAndFlush(
                new com.connectx.message.entity.MessageReaction(messageRef, userRef, cleanReaction));
    }

    /**
     * Isolation is deliberately widened to READ_COMMITTED (MySQL's default is REPEATABLE READ):
     * this method's own consistent-read snapshot is established by its first SELECT below, and
     * under REPEATABLE READ that snapshot predates the REQUIRES_NEW insert further down (see
     * insertReactionInNewTransaction) -- a genuinely separate, already-committed transaction by
     * the time this method reads the message's full reaction list again to build its response
     * and WebSocket broadcast, but invisible to a snapshot taken before it committed. The net
     * effect was that a user's first reaction on a message would persist correctly (a page
     * reload shows it, since that's a fresh transaction/snapshot) but silently never appear in
     * the REST response or the live broadcast that the UI actually renders from -- reactions
     * looked "not working" even though every insert was landing in the database. READ_COMMITTED
     * makes every SELECT in this method take a fresh snapshot, so it sees its own REQUIRES_NEW
     * work as soon as that work commits.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MessageDto addOrUpdateReaction(Long currentUserId, Long messageId, String reaction) {
        if (reaction == null || reaction.trim().isEmpty()) {
            return removeReaction(currentUserId, messageId);
        }
        String cleanReaction = reaction.trim();

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        Long conversationId = message.getConversation().getId();
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        com.connectx.message.entity.MessageReaction existingReaction = messageReactionRepository.findByMessageIdAndUserId(messageId, currentUserId).orElse(null);
        if (existingReaction != null) {
            if (existingReaction.getReaction().equals(cleanReaction)) {
                messageReactionRepository.delete(existingReaction);
            } else {
                existingReaction.setReaction(cleanReaction);
                messageReactionRepository.save(existingReaction);
            }
        } else {
            try {
                self.insertReactionInNewTransaction(messageId, currentUserId, cleanReaction);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Lost a race with a concurrent insert for the same (message, user) pair.
                // It ran in its own isolated transaction (see insertReactionInNewTransaction),
                // which rolled back on its own -- this (outer) transaction's connection was
                // never touched by that failure, so it's safe to catch here and continue.
                // The winner's row is already committed; apply our reaction on top of it
                // instead of surfacing a 500 to the loser of the race.
                messageReactionRepository.findByMessageIdAndUserId(messageId, currentUserId)
                        .ifPresent(existing -> {
                            existing.setReaction(cleanReaction);
                            messageReactionRepository.save(existing);
                        });
            }
        }

        List<com.connectx.message.entity.MessageReaction> allReactions = messageReactionRepository.findByMessageIdWithUsers(messageId);
        List<com.connectx.message.dto.MessageReactionDto> reactionDtos = allReactions.stream()
                .map(com.connectx.message.dto.MessageReactionDto::fromEntity)
                .collect(Collectors.toList());

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("messageId", messageId);
        wsPayload.put("conversationId", conversationId);
        wsPayload.put("userId", currentUserId);
        wsPayload.put("username", user.getUsername());
        wsPayload.put("reaction", cleanReaction);
        wsPayload.put("reactions", reactionDtos);

        WsEvent wsEvent = WsEvent.of("MESSAGE_REACTION_UPDATE", wsPayload);

        boolean isGroupConvForReaction = message.getConversation().getType() == ConversationType.GROUP;
        afterCommitExecutor.runAfterCommit(() -> {
            if (!isGroupConvForReaction) {
                messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, wsEvent);
            }
            List<ConversationMember> members = conversationMemberRepository.findByConversationIdAndDeletedAtIsNullWithUsers(conversationId);
            for (ConversationMember m : members) {
                if (m.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", wsEvent);
                }
            }
        });

        MessageDto resultDto = MessageDto.fromEntity(message);
        resultDto.setReactions(reactionDtos);
        return resultDto;
    }

    @Transactional
    public MessageDto removeReaction(Long currentUserId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "Message not found"));

        Long conversationId = message.getConversation().getId();
        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        messageReactionRepository.findByMessageIdAndUserId(messageId, currentUserId)
                .ifPresent(messageReactionRepository::delete);

        List<com.connectx.message.entity.MessageReaction> allReactions = messageReactionRepository.findByMessageIdWithUsers(messageId);
        List<com.connectx.message.dto.MessageReactionDto> reactionDtos = allReactions.stream()
                .map(com.connectx.message.dto.MessageReactionDto::fromEntity)
                .collect(Collectors.toList());

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("messageId", messageId);
        wsPayload.put("conversationId", conversationId);
        wsPayload.put("userId", currentUserId);
        wsPayload.put("username", user.getUsername());
        wsPayload.put("reaction", null);
        wsPayload.put("reactions", reactionDtos);

        WsEvent wsEvent = WsEvent.of("MESSAGE_REACTION_UPDATE", wsPayload);

        boolean isGroupConvForReaction = message.getConversation().getType() == ConversationType.GROUP;
        afterCommitExecutor.runAfterCommit(() -> {
            if (!isGroupConvForReaction) {
                messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, wsEvent);
            }
            List<ConversationMember> members = conversationMemberRepository.findByConversationIdAndDeletedAtIsNullWithUsers(conversationId);
            for (ConversationMember m : members) {
                if (m.getUser() != null) {
                    messagingTemplate.convertAndSendToUser(m.getUser().getUsername(), "/queue/messages", wsEvent);
                }
            }
        });

        MessageDto resultDto = MessageDto.fromEntity(message);
        resultDto.setReactions(reactionDtos);
        return resultDto;
    }
}
