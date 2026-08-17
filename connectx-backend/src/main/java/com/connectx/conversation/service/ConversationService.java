package com.connectx.conversation.service;

import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;
    private final UserConnectionRepository userConnectionRepository;
    private final UserBlockRepository userBlockRepository;
    private final com.connectx.message.repository.MessageRepository messageRepository;
    private final com.connectx.message.repository.MessageUserStateRepository messageUserStateRepository;
    private final com.connectx.message.repository.MessageStarRepository messageStarRepository;
    private final com.connectx.message.repository.MessageReactionRepository messageReactionRepository;
    private final com.connectx.media.repository.MessageMediaRepository messageMediaRepository;
    private final com.connectx.media.storage.MediaStorage mediaStorage;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository conversationMemberRepository,
                               UserRepository userRepository,
                               UserConnectionRepository userConnectionRepository,
                               UserBlockRepository userBlockRepository,
                               com.connectx.message.repository.MessageRepository messageRepository,
                               com.connectx.message.repository.MessageUserStateRepository messageUserStateRepository,
                               com.connectx.message.repository.MessageStarRepository messageStarRepository,
                               com.connectx.message.repository.MessageReactionRepository messageReactionRepository,
                               com.connectx.media.repository.MessageMediaRepository messageMediaRepository,
                               com.connectx.media.storage.MediaStorage mediaStorage,
                               org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
        this.userConnectionRepository = userConnectionRepository;
        this.userBlockRepository = userBlockRepository;
        this.messageRepository = messageRepository;
        this.messageUserStateRepository = messageUserStateRepository;
        this.messageStarRepository = messageStarRepository;
        this.messageReactionRepository = messageReactionRepository;
        this.messageMediaRepository = messageMediaRepository;
        this.mediaStorage = mediaStorage;
        this.messagingTemplate = messagingTemplate;
    }

    // READ_COMMITTED (not the MySQL default REPEATABLE READ): the pessimistic lock below can
    // block waiting for a concurrent createOrGet call on the same pair to commit, and once
    // unblocked, the existence check right after it must see that just-committed conversation.
    // Under REPEATABLE READ, this method's earlier plain SELECTs (e.g. the target-user lookup)
    // would already have fixed a consistent-read snapshot from before the wait, so the
    // existence check would miss the other transaction's commit and both sides would create
    // their own conversation -- the same cross-transaction visibility gap documented for
    // MessageService#addOrUpdateReaction's REQUIRES_NEW + re-read pattern.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConversationDto createOrGetDirectConversation(Long currentUserId, CreateDirectConversationDto dto) {
        Long targetUserId = dto.getUserId();

        if (currentUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_CHAT_NOT_ALLOWED", "Cannot start direct chat with yourself");
        }

        userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));

        // Serialize concurrent createOrGet attempts for this exact pair (regardless of who
        // initiates) by taking a pessimistic write lock on the deterministically-lower user id.
        // Conversations have no DB-level uniqueness guard for a DIRECT pair (unlike the Stage 1
        // connections table), so without this, two concurrent requests could both pass the
        // existence check below and each insert their own conversation. Held for the rest of
        // this transaction, covering both the existence check and, on the new-conversation path,
        // the connection-authorization check and the insert itself.
        userRepository.findByIdForUpdate(Math.min(currentUserId, targetUserId));

        Optional<Conversation> existingOpt = conversationRepository.findDirectConversationBetweenUsers(currentUserId, targetUserId);
        if (existingOpt.isPresent()) {
            Conversation existing = existingOpt.get();
            ConversationMember currentMembership = conversationMemberRepository
                    .findByConversationIdAndUserId(existing.getId(), currentUserId)
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation"));

            if (currentMembership.getDeletedAt() != null) {
                log.info("Restoring previously deleted direct conversation on explicit start: conversationId={}, userId={}",
                        existing.getId(), currentUserId);
                forceRestoreConversationForUser(existing.getId(), currentUserId);
                User currentUser = userRepository.findById(currentUserId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));
                com.connectx.websocket.dto.WsEvent restoredEvent = com.connectx.websocket.dto.WsEvent.of(
                        "CONVERSATION_RESTORED",
                        java.util.Map.of("conversationId", existing.getId())
                );
                messagingTemplate.convertAndSendToUser(currentUser.getUsername(), "/queue/messages", restoredEvent);
                return enrichConversationDto(existing, currentUserId);
            }

            return enrichConversationDto(existing, currentUserId);
        }

        // A block takes precedence over connection state for NEW conversation creation -- checked
        // before the connection-required check below so a blocked pair always sees BLOCKED, never
        // NOT_CONNECTED. Pre-existing conversations (checked above, already returned by this
        // point) are exempt: a block never deletes or hides an existing DIRECT conversation, it
        // only prevents a brand-new relationship from being formed.
        if (userBlockRepository.existsEitherDirection(currentUserId, targetUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "You cannot start a conversation with this user");
        }

        // No existing DIRECT conversation for this pair -- a brand-new one may only be created
        // between connected users. Pre-existing conversations (checked above) are exempt from
        // this: users who already have a DIRECT conversation from before Stage 1.5 keep working
        // even though no connections row exists for them.
        Long lowId = Math.min(currentUserId, targetUserId);
        Long highId = Math.max(currentUserId, targetUserId);
        if (!userConnectionRepository.existsByUserLowIdAndUserHighId(lowId, highId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONNECTED",
                    "You must be connected with this user to start a conversation");
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));
        User targetUser = userRepository.findById(targetUserId).get();

        Conversation conversation = new Conversation(ConversationType.DIRECT);
        Conversation savedConversation = conversationRepository.save(conversation);

        ConversationMember member1 = new ConversationMember(savedConversation, currentUser);
        ConversationMember member2 = new ConversationMember(savedConversation, targetUser);

        conversationMemberRepository.save(member1);
        conversationMemberRepository.save(member2);

        savedConversation.getMembers().add(member1);
        savedConversation.getMembers().add(member2);

        return enrichConversationDto(savedConversation, currentUserId);
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getUserConversations(Long currentUserId) {
        List<Conversation> activeConversations = conversationRepository.findActiveConversationsForUser(currentUserId);
        if (activeConversations.isEmpty()) {
            return List.of();
        }

        List<Long> conversationIds = activeConversations.stream()
                .map(Conversation::getId)
                .collect(Collectors.toList());

        List<ConversationMember> allMembers = conversationMemberRepository.findByConversationIdInWithUsers(conversationIds);
        Map<Long, List<ConversationMember>> membersByConvId = allMembers.stream()
                .collect(Collectors.groupingBy(cm -> cm.getConversation().getId()));

        // M-04: fetch every conversation's latest-message preview in one round trip instead of
        // one query per conversation (findLatestMessageInConversation, called from
        // enrichConversationDto below, was previously invoked once per conversation here).
        Map<Long, Message> latestMessageByConvId = messageRepository
                .findLatestMessagesForConversations(conversationIds, currentUserId).stream()
                .collect(Collectors.toMap(m -> m.getConversation().getId(), m -> m));

        return activeConversations.stream()
                .map(conversation -> {
                    List<ConversationMember> members = membersByConvId.getOrDefault(conversation.getId(), List.of());
                    ConversationDto dto = buildConversationDto(conversation, currentUserId, members);
                    applyLastMessage(dto, latestMessageByConvId.get(conversation.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ConversationDto getConversationById(Long currentUserId, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        boolean isMember = conversationMemberRepository.existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, currentUserId);
        if (!isMember) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_CONVERSATION_MEMBER", "You are not a member of this conversation");
        }

        return enrichConversationDto(conversation, currentUserId);
    }

    private ConversationDto enrichConversationDto(Conversation conversation, Long currentUserId) {
        List<ConversationMember> members = conversationMemberRepository.findByConversationIdWithUsers(conversation.getId());
        return enrichConversationDtoWithMembers(conversation, currentUserId, members);
    }

    private ConversationDto enrichConversationDtoWithMembers(Conversation conversation, Long currentUserId, List<ConversationMember> members) {
        ConversationDto dto = buildConversationDto(conversation, currentUserId, members);

        ConversationMember currentMember = members.stream()
                .filter(m -> m.getUser() != null && m.getUser().getId().equals(currentUserId))
                .findFirst()
                .orElse(null);
        Instant clearedAfter = currentMember != null ? currentMember.getClearedAt() : null;

        List<Message> latestMessages = messageRepository.findLatestMessageInConversation(
                conversation.getId(),
                currentUserId,
                clearedAfter,
                PageRequest.of(0, 1)
        );
        applyLastMessage(dto, latestMessages.isEmpty() ? null : latestMessages.get(0));
        return dto;
    }

    private ConversationDto buildConversationDto(Conversation conversation, Long currentUserId, List<ConversationMember> members) {
        ConversationDto dto = new ConversationDto();
        dto.setId(conversation.getId());
        dto.setType(conversation.getType().name());
        dto.setCreatedAt(conversation.getCreatedAt());
        dto.setUpdatedAt(conversation.getUpdatedAt());

        dto.setMembers(members.stream()
                .map(ConversationMemberDto::fromEntity)
                .collect(Collectors.toList()));

        ConversationMember currentMember = members.stream()
                .filter(m -> m.getUser() != null && m.getUser().getId().equals(currentUserId))
                .findFirst()
                .orElse(null);

        if (currentMember != null) {
            dto.setPinned(currentMember.isPinned());
            dto.setPinnedAt(currentMember.getPinnedAt());
            dto.setMutedUntil(currentMember.getMutedUntil());
            dto.setMuted(currentMember.getMutedUntil() != null && currentMember.getMutedUntil().isAfter(Instant.now()));
            dto.setArchived(currentMember.isArchived());
            dto.setArchivedAt(currentMember.getArchivedAt());
            dto.setManuallyMarkedUnread(currentMember.isManuallyMarkedUnread());
        }

        return dto;
    }

    private void applyLastMessage(ConversationDto dto, Message latest) {
        if (latest == null) {
            return;
        }
        dto.setLastMessageId(latest.getId());
        dto.setLastMessageSentAt(latest.getSentAt());
        dto.setLastMessageDeletedForEveryone(latest.isDeletedForEveryone());
        dto.setLastMessageType(latest.getMessageType() != null ? latest.getMessageType().name() : "TEXT");
        if (latest.getMessageType() == MessageType.LOCATION) {
            dto.setLastMessageCaption(latest.getLocationLabel());
        } else {
            dto.setLastMessageCaption(latest.getCaption());
        }
        if (latest.getSenderUser() != null) {
            dto.setLastMessageSenderUserId(latest.getSenderUser().getId());
        } else if (latest.getSenderDevice() != null && latest.getSenderDevice().getUser() != null) {
            dto.setLastMessageSenderUserId(latest.getSenderDevice().getUser().getId());
        }
    }

    @Transactional
    public ConversationDto pinConversation(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }

        if (!member.isPinned()) {
            long pinnedCount = conversationMemberRepository.countByUserIdAndPinnedTrueAndDeletedAtIsNull(currentUserId);
            if (pinnedCount >= 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_EXCEEDED", "You can pin up to 2 chats.");
            }
            member.setPinned(true);
            member.setPinnedAt(Instant.now());
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto unpinConversation(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.isPinned()) {
            member.setPinned(false);
            member.setPinnedAt(null);
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    /**
     * Re-enables a per-user hidden conversation when a qualifying event occurs
     * (for example, a new message sent after the user deleted the chat).
     * Returns true when the conversation was previously hidden for this user.
     */
    @Transactional
    public boolean restoreConversationVisibilityForUser(Long conversationId, Long userId, Instant triggerAt) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElse(null);
        if (member == null || member.getDeletedAt() == null) {
            return false;
        }

        Instant effectiveTrigger = triggerAt != null ? triggerAt : Instant.now();
        if (!effectiveTrigger.isAfter(member.getDeletedAt())) {
            log.debug(
                    "Skipping conversation restore: conversationId={}, userId={}, triggerAt={}, deletedAt={}",
                    conversationId, userId, effectiveTrigger, member.getDeletedAt()
            );
            return false;
        }

        member.setDeletedAt(null);
        conversationMemberRepository.save(member);
        messageUserStateRepository.deleteByConversationIdAndUserId(conversationId, userId);
        log.info("Restored conversation visibility: conversationId={}, userId={}", conversationId, userId);
        return true;
    }

    /**
     * Unconditionally re-adds a per-user hidden conversation to the user's list.
     * Used when the user explicitly chooses to start/open a chat again.
     */
    @Transactional
    public void forceRestoreConversationForUser(Long conversationId, Long userId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElse(null);
        if (member == null || member.getDeletedAt() == null) {
            return;
        }

        member.setDeletedAt(null);
        conversationMemberRepository.saveAndFlush(member);
        messageUserStateRepository.deleteByConversationIdAndUserId(conversationId, userId);
        log.info("Force-restored conversation visibility: conversationId={}, userId={}", conversationId, userId);
    }

    @Transactional
    public void deleteConversationForUser(Long currentUserId, Long conversationId) {
        log.info("Conversation delete requested: conversationId={}, userId={}", conversationId, currentUserId);

        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found for user"));

        if (member.getDeletedAt() != null) {
            log.debug("Conversation already deleted for user: conversationId={}, userId={}", conversationId, currentUserId);
            return;
        }

        member.setDeletedAt(Instant.now());
        conversationMemberRepository.saveAndFlush(member);
        log.info("Marked conversation deleted for user: conversationId={}, userId={}", conversationId, currentUserId);

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        // 1. Mark ALL existing messages in this conversation as deleted for current user, in a
        // single bulk statement (M-05: previously an exists-check + insert per message, which
        // could be 100+ round trips for one "delete conversation" click).
        messageUserStateRepository.bulkInsertDeletedStateForConversation(conversationId, currentUserId, Instant.now());

        // 2. Check if all members have deleted this conversation via direct DB count query
        long activeMembers = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(conversationId);
        if (activeMembers == 0) {
            // H-05: message_reactions and message_stars both hold a NOT NULL FK to
            // messages.id, so they must be cleared before the messages themselves or the
            // DELETE below violates that FK and the whole conversation deletion fails.
            // message_media holds a NOT NULL FK to conversations.id, so it must be cleared
            // before the conversation row -- and since removing the DB row is the only place
            // that "forgets" about a file, the physical files must be deleted here too, or
            // they're permanently orphaned on disk (no other code path ever cleans them up).
            messageReactionRepository.deleteByConversationId(conversationId);
            messageStarRepository.deleteByConversationId(conversationId);
            messageUserStateRepository.deleteByConversationId(conversationId);

            List<com.connectx.media.entity.MessageMedia> mediaToDelete = messageMediaRepository.findByConversationId(conversationId);
            for (com.connectx.media.entity.MessageMedia media : mediaToDelete) {
                try {
                    mediaStorage.delete(media.getStorageKey(), media.getMimeType());
                } catch (Exception ex) {
                    // A single unreadable/locked file shouldn't block cleanup of the rest or
                    // the DB-level delete -- worst case here is one orphaned file, not an
                    // undeletable conversation.
                    log.error("Failed to delete media file during conversation cleanup: conversationId={}, mediaId={}, storageKey={}",
                            conversationId, media.getId(), media.getStorageKey(), ex);
                }
            }
            messageMediaRepository.deleteByConversationId(conversationId);

            messageRepository.deleteByConversationId(conversationId);
            conversationMemberRepository.deleteByConversationId(conversationId);
            conversationRepository.deleteById(conversationId);
        }

        // 3. Send real-time CONVERSATION_DELETED event ONLY to the deleting user's private queue
        com.connectx.websocket.dto.WsEvent deleteEvent = com.connectx.websocket.dto.WsEvent.of(
            "CONVERSATION_DELETED",
            java.util.Map.of("conversationId", conversationId, "deletedByUserId", currentUserId)
        );
        messagingTemplate.convertAndSendToUser(currentUser.getUsername(), "/queue/messages", deleteEvent);
    }

    @Transactional
    public void clearConversationForUser(Long currentUserId, Long conversationId) {
        log.info("Conversation clear requested: conversationId={}, userId={}", conversationId, currentUserId);

        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found for user"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found for user");
        }

        Instant clearedAt = Instant.now();
        member.setClearedAt(clearedAt);
        conversationMemberRepository.saveAndFlush(member);
        log.info("Marked conversation cleared for user: conversationId={}, userId={}, clearedAt={}",
                conversationId, currentUserId, clearedAt);

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        com.connectx.websocket.dto.WsEvent clearEvent = com.connectx.websocket.dto.WsEvent.of(
                "CONVERSATION_CLEARED",
                java.util.Map.of(
                        "conversationId", conversationId,
                        "clearedAt", clearedAt.toString()
                )
        );
        messagingTemplate.convertAndSendToUser(currentUser.getUsername(), "/queue/messages", clearEvent);
    }

    @Transactional
    public ConversationDto muteConversation(Long currentUserId, Long conversationId, Instant mutedUntil) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }

        member.setMutedUntil(mutedUntil != null ? mutedUntil : Instant.parse("9999-12-31T23:59:59Z"));
        conversationMemberRepository.save(member);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto unmuteConversation(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }

        member.setMutedUntil(null);
        conversationMemberRepository.save(member);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto archiveConversation(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }

        if (!member.isArchived()) {
            member.setArchived(true);
            member.setArchivedAt(Instant.now());
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto unarchiveConversation(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.isArchived()) {
            member.setArchived(false);
            member.setArchivedAt(null);
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto markConversationUnread(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found");
        }

        if (!member.isManuallyMarkedUnread()) {
            member.setManuallyMarkedUnread(true);
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    @Transactional
    public ConversationDto markConversationRead(Long currentUserId, Long conversationId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));

        if (member.isManuallyMarkedUnread()) {
            member.setManuallyMarkedUnread(false);
            conversationMemberRepository.save(member);
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found"));
        return enrichConversationDto(conversation, currentUserId);
    }

    /**
     * Clears the manual "mark unread" override when the user genuinely reads the
     * conversation, so the flag can't outlive an actual read. Silent no-op if the
     * member row doesn't exist or the flag isn't set — called opportunistically
     * from the message-read pipeline (see MessageService#markConversationAsRead).
     */
    @Transactional
    public void clearManuallyMarkedUnreadIfSet(Long conversationId, Long userId) {
        conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
                .filter(ConversationMember::isManuallyMarkedUnread)
                .ifPresent(member -> {
                    member.setManuallyMarkedUnread(false);
                    conversationMemberRepository.save(member);
                });
    }
}
