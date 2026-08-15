package com.connectx.conversation.service;

import com.connectx.common.exception.ApiException;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;
    private final com.connectx.message.repository.MessageRepository messageRepository;
    private final com.connectx.message.repository.MessageUserStateRepository messageUserStateRepository;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository conversationMemberRepository,
                               UserRepository userRepository,
                               com.connectx.message.repository.MessageRepository messageRepository,
                               com.connectx.message.repository.MessageUserStateRepository messageUserStateRepository,
                               org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.messageUserStateRepository = messageUserStateRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public ConversationDto createOrGetDirectConversation(Long currentUserId, CreateDirectConversationDto dto) {
        Long targetUserId = dto.getUserId();

        if (currentUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_CHAT_NOT_ALLOWED", "Cannot start direct chat with yourself");
        }

        userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));

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
        return conversationRepository.findActiveConversationsForUser(currentUserId).stream()
                .map(conversation -> enrichConversationDto(conversation, currentUserId))
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
        ConversationDto dto = new ConversationDto();
        dto.setId(conversation.getId());
        dto.setType(conversation.getType().name());
        dto.setCreatedAt(conversation.getCreatedAt());
        dto.setUpdatedAt(conversation.getUpdatedAt());

        List<ConversationMember> members = conversationMemberRepository.findByConversationIdWithUsers(conversation.getId());
        dto.setMembers(members.stream()
                .map(ConversationMemberDto::fromEntity)
                .collect(Collectors.toList()));

        ConversationMember currentMember = conversationMemberRepository
                .findByConversationIdAndUserId(conversation.getId(), currentUserId)
                .orElse(null);
        if (currentMember != null) {
            dto.setPinned(currentMember.isPinned());
            dto.setPinnedAt(currentMember.getPinnedAt());
            dto.setMutedUntil(currentMember.getMutedUntil());
            dto.setMuted(currentMember.getMutedUntil() != null && currentMember.getMutedUntil().isAfter(Instant.now()));
        }

        Instant clearedAfter = currentMember != null ? currentMember.getClearedAt() : null;

        List<Message> latestMessages = messageRepository.findLatestMessageInConversation(
                conversation.getId(),
                currentUserId,
                clearedAfter,
                PageRequest.of(0, 1)
        );
        if (!latestMessages.isEmpty()) {
            Message latest = latestMessages.get(0);
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
        return dto;
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

        // 1. Mark ALL existing messages in this conversation as deleted for current user
        List<com.connectx.message.entity.Message> allMessages = messageRepository.findByConversationId(conversationId);
        for (com.connectx.message.entity.Message msg : allMessages) {
            if (!messageUserStateRepository.existsByMessageIdAndUserId(msg.getId(), currentUserId)) {
                com.connectx.message.entity.MessageUserState userState = new com.connectx.message.entity.MessageUserState(msg, currentUser);
                messageUserStateRepository.save(userState);
            }
        }
        messageUserStateRepository.flush();

        // 2. Check if all members have deleted this conversation via direct DB count query
        long activeMembers = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(conversationId);
        if (activeMembers == 0) {
            messageUserStateRepository.deleteByConversationId(conversationId);
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
}
