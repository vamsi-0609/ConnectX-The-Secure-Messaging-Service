package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.WhoCanEditGroupInfo;
import com.connectx.group.entity.WhoCanInvite;
import com.connectx.group.entity.WhoCanSendMessages;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.ProfileVisibilityService;
import com.connectx.websocket.dto.WsEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Groups Stage 1 backend foundation. A GROUP is a Conversation (type=GROUP) + a ChatGroup row +
 * ConversationMember rows with a role -- deliberately not a parallel hierarchy, so every other
 * conversation-scoped mechanism (messaging, WebSocket topic authorization from Stage 0, pin/mute/
 * archive) keeps working against a group's conversationId without modification.
 * <p>
 * ConversationService remains responsible for generic, DIRECT-or-GROUP-agnostic conversation
 * operations; this service owns everything group-specific (creation, role assignment, membership
 * limits) so ConversationService doesn't need to grow group-shaped branches.
 */
@Service
public class GroupService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final UserRepository userRepository;
    private final ProfileVisibilityService profileVisibilityService;
    private final GroupAuthorizationService groupAuthorizationService;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupService(ConversationRepository conversationRepository,
                         ConversationMemberRepository conversationMemberRepository,
                         ChatGroupRepository chatGroupRepository,
                         GroupInvitationRepository groupInvitationRepository,
                         UserRepository userRepository,
                         ProfileVisibilityService profileVisibilityService,
                         GroupAuthorizationService groupAuthorizationService,
                         SimpMessagingTemplate messagingTemplate) {
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.groupInvitationRepository = groupInvitationRepository;
        this.userRepository = userRepository;
        this.profileVisibilityService = profileVisibilityService;
        this.groupAuthorizationService = groupAuthorizationService;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public GroupDto createGroup(Long currentUserId, CreateGroupRequestDto dto) {
        String name = validateName(dto.getName());
        String description = validateDescription(dto.getDescription());

        // The authenticated caller is always the creator/owner -- CreateGroupRequestDto has no
        // creatorUserId or role field, so there is nothing here for a client to forge either with.
        User creator = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));

        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.GROUP));

        ChatGroup chatGroup = new ChatGroup(conversation, name, description, creator);
        chatGroup = chatGroupRepository.save(chatGroup);

        // Self-invocation, so addMember's own @Transactional(isolation=READ_COMMITTED) does not
        // apply here via AOP -- this runs at whatever isolation createGroup's own @Transactional
        // uses instead (MySQL's default REPEATABLE READ). That's fine specifically for this call:
        // conversationId was just generated by this same transaction, so no other transaction can
        // possibly reference it yet -- there is no concurrent writer for the isolation level to
        // protect against here. The pessimistic lock inside addMember still executes correctly
        // regardless (it goes through ChatGroupRepository's own proxy, not this class's), it's
        // only the isolation-level override that's skipped. GroupInvitationService's calls into
        // this same 5-arg overload are genuine cross-bean calls where the annotation does apply,
        // which is the path that actually needs it (see that method's javadoc).
        ConversationMember ownerMembership = addMember(conversation, creator, GroupRole.OWNER, null, false);
        conversation.getMembers().add(ownerMembership);

        return buildGroupDto(conversation, chatGroup, 1L, GroupRole.OWNER);
    }

    @Transactional(readOnly = true)
    public GroupDto getGroupDetails(Long currentUserId, Long groupId) {
        ChatGroup chatGroup = groupAuthorizationService.requireActiveMember(currentUserId, groupId);
        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        GroupRole viewerRole = conversationMemberRepository.findByConversationIdAndUserId(groupId, currentUserId)
                .map(ConversationMember::getRole)
                .orElse(null);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, viewerRole);
    }

    @Transactional(readOnly = true)
    public List<ConversationMemberDto> getGroupMembers(Long currentUserId, Long groupId) {
        groupAuthorizationService.requireActiveMember(currentUserId, groupId);

        List<ConversationMember> members = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<User> memberUsers = members.stream().map(ConversationMember::getUser).collect(Collectors.toList());
        Map<Long, Boolean> photoVisibilityByUserId = profileVisibilityService.resolvePhotoVisibility(currentUserId, memberUsers);

        return members.stream()
                .map(m -> ConversationMemberDto.fromEntity(m, photoVisibilityByUserId.getOrDefault(m.getUser().getId(), false)))
                .collect(Collectors.toList());
    }

    /**
     * Groups Stage 4: updates the group's three policy settings (docs/CONNECTX_GROUP_ARCHITECTURE.md
     * §5.1/§6 -- "Change group settings (the 3 ENUMs) -- Owner only"). Every field in the request is
     * optional; only the ones actually supplied are validated and applied. All-or-nothing: an
     * invalid value in any supplied field throws before any field is set, and since this is a
     * single @Transactional method with no intermediate flush, nothing partial is ever persisted --
     * either every valid change in the request commits, or (on an invalid field) none of them do.
     * Existing membership, invitations, connections, and blocks are all untouched -- a settings
     * change only affects future authorization decisions, never retroactively.
     */
    @Transactional
    public GroupDto updateSettings(Long actorUserId, Long groupId, UpdateGroupSettingsRequestDto dto) {
        groupAuthorizationService.requireOwner(actorUserId, groupId);

        ChatGroup chatGroup = chatGroupRepository.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));

        if (dto.getWhoCanInvite() != null) {
            chatGroup.setWhoCanInvite(parseEnum(WhoCanInvite.class, dto.getWhoCanInvite(), "whoCanInvite"));
        }
        if (dto.getWhoCanSendMessages() != null) {
            chatGroup.setWhoCanSendMessages(parseEnum(WhoCanSendMessages.class, dto.getWhoCanSendMessages(), "whoCanSendMessages"));
        }
        if (dto.getWhoCanEditGroupInfo() != null) {
            chatGroup.setWhoCanEditGroupInfo(parseEnum(WhoCanEditGroupInfo.class, dto.getWhoCanEditGroupInfo(), "whoCanEditGroupInfo"));
        }

        chatGroupRepository.save(chatGroup);

        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, GroupRole.OWNER);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String rawValue, String fieldName) {
        try {
            return Enum.valueOf(enumType, rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTING_VALUE",
                    fieldName + " must be one of: " + java.util.Arrays.toString(enumType.getEnumConstants()));
        }
    }

    /**
     * Groups Stage 3: OWNER-only promote/demote (ADMIN &lt;-&gt; MEMBER). No locking -- per
     * docs/CONNECTX_GROUP_ARCHITECTURE.md's own concurrency analysis, a role field update has no
     * uniqueness constraint to race against and is idempotent-to-set, so last-write-wins under two
     * concurrent changes is an accepted, harmless outcome (unlike an insert, where a race could
     * duplicate a row or blow past the member cap).
     */
    @Transactional
    public ConversationMemberDto changeRole(Long actorUserId, Long groupId, Long targetUserId, GroupRole newRole) {
        groupAuthorizationService.requireCanChangeRole(actorUserId, groupId, targetUserId, newRole);

        ConversationMember target = conversationMemberRepository.findByConversationIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "Target user is not an active member of this group"));
        target.setRole(newRole);
        conversationMemberRepository.save(target);

        boolean photoVisible = profileVisibilityService.isProfilePhotoVisible(target.getUser(), actorUserId);
        return ConversationMemberDto.fromEntity(target, photoVisible);
    }

    /**
     * Groups Stage 3: OWNER may remove any non-owner member (including admins); ADMIN may remove a
     * plain MEMBER only -- see GroupAuthorizationService#requireCanRemoveMember for the full
     * matrix, decided entirely there. Soft-deletes only (existing architecture -- messages, the
     * group, connections, and any block relationship are all untouched), and cancels any other
     * PENDING invitation for this exact (group, user) pair so a stale invitation predating (or
     * racing) this removal can't later be accepted to silently undo an admin's decision -- the
     * removed user still gets a completely clean path back in via a *fresh* invitation, per the
     * Stage 1.5/2 consent rule; this only closes the "accept an old leftover invite instead"
     * loophole. No pessimistic lock: removal only ever decreases the active-member count, so it
     * can never itself cause the 50-cap to be exceeded, and per the architecture doc's own
     * concurrency analysis a second concurrent removal of the same target simply lands on
     * NOT_GROUP_MEMBER (already removed) -- an accepted, harmless outcome requiring no special
     * handling, mirroring ConnectionService#removeConnection's identical precedent.
     */
    @Transactional
    public void removeMember(Long actorUserId, Long groupId, Long targetUserId) {
        groupAuthorizationService.requireCanRemoveMember(actorUserId, groupId, targetUserId);
        endMembership(groupId, targetUserId);
    }

    /**
     * Groups Stage 3: voluntary leave. OWNER is blocked (see
     * GroupAuthorizationService#requireCanLeave); MEMBER/ADMIN always allowed. Shares
     * endMembership with removeMember -- same soft-delete + stale-invitation-cancellation
     * behavior, since a voluntary leave must be exactly as resistant to a stale-invitation bypass
     * as an admin-initiated removal (Stage 3's re-entry-consent rule draws no distinction between
     * the two once membership has ended).
     */
    @Transactional
    public void leaveGroup(Long actorUserId, Long groupId) {
        groupAuthorizationService.requireCanLeave(actorUserId, groupId);
        endMembership(groupId, actorUserId);
    }

    /**
     * Groups Stage 7: owner-only, immediate group deletion. Deliberately does NOT reuse
     * ConversationService#deleteConversationForUser's per-user-hide-until-unanimous model, and does
     * NOT hard-delete the conversation, messages, or chat_groups row -- history is preserved, per
     * this stage's instruction to prefer the safest lifecycle consistent with the existing
     * architecture over inventing a new deletion strategy. Instead this soft-deletes every currently
     * active membership row, including the OWNER's own -- something no other path in this codebase
     * ever does (the owner can neither leave, see
     * {@link GroupAuthorizationService#requireCanLeave}, nor be removed, see
     * {@link GroupAuthorizationService#requireCanRemoveMember}). That makes "the owner's own row has
     * deletedAt set" an unambiguous, schema-free signal that this exact group was deleted, reusing
     * the existing deletedAt convention rather than adding a new column. Every enforcement point
     * that matters already gates on deletedAt IS NULL -- group view/read
     * ({@link GroupAuthorizationService#requireActiveMember}), sending
     * ({@link GroupAuthorizationService#requireCanSendMessage}), inviting
     * ({@link GroupAuthorizationService#evaluateAddMember}), and WebSocket SUBSCRIBE
     * (WebSocketAuthChannelInterceptor's existing Stage 0 check) -- so this one bulk write is
     * sufficient; no second authorization mechanism is introduced anywhere. A repeat delete attempt
     * on an already-deleted group fails at {@code requireOwner} itself, since the actor is no longer
     * an ACTIVE_MEMBER.
     * <p>
     * Also bulk-cancels every PENDING invitation for the group (not just the acting owner's own),
     * mirroring {@link #endMembership}'s existing stale-invitation cleanup, so no old invitation can
     * later be accepted/rejected/cancelled against a group that no longer has anyone active in it.
     * Group E2EE key rows ({@code group_member_keys}) and the Stage 6C key API are untouched, per
     * this stage's explicit instruction not to touch key material.
     * <p>
     * Broadcasts the same {@code CONVERSATION_DELETED} event
     * {@code ConversationService#deleteConversationForUser} already sends for a DIRECT chat, both to
     * the group's topic (for anyone with it open right now) and to every formerly-active member's
     * private queue (so their conversation list updates even if they aren't currently subscribed) --
     * the frontend's existing CONVERSATION_DELETED handler requires no changes to pick this up for a
     * group.
     */
    @Transactional
    public void deleteGroup(Long actorUserId, Long groupId) {
        groupAuthorizationService.requireOwner(actorUserId, groupId);

        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);

        Instant now = Instant.now();
        conversationMemberRepository.markAllActiveAsDeleted(groupId, now);
        groupInvitationRepository.cancelAllPendingForGroup(groupId, now);

        WsEvent deleteEvent = WsEvent.of("CONVERSATION_DELETED",
                Map.of("conversationId", groupId, "deletedByUserId", actorUserId));
        messagingTemplate.convertAndSend("/topic/conversation/" + groupId, deleteEvent);
        for (ConversationMember member : activeMembers) {
            messagingTemplate.convertAndSendToUser(member.getUser().getUsername(), "/queue/messages", deleteEvent);
        }
    }

    private void endMembership(Long groupId, Long userId) {
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "User is not an active member of this group"));
        Instant now = Instant.now();
        member.setDeletedAt(now);
        conversationMemberRepository.save(member);
        groupInvitationRepository.cancelAllPendingForGroupAndInvitee(groupId, userId, now);
    }

    /**
     * Adds a brand-new active membership row for {@code user} in {@code conversation}, under the
     * central {@link GroupAuthorizationService#MAX_ACTIVE_GROUP_MEMBERS} cap. Rejects an existing
     * soft-deleted (INACTIVE) row rather than restoring it -- see the 5-arg overload's javadoc for
     * when restoration is allowed instead. Convenience overload for every call site that must
     * never restore (group creation; the DIRECT_ADD outcome of a fresh invitation, which by
     * construction only ever targets a NEVER_MEMBER user -- see GroupAuthorizationService#evaluateAddMember).
     * <p>
     * Carries its own {@code @Transactional} (matching the 5-arg overload's) rather than relying
     * on the 5-arg call below to establish one: that call is a same-class self-invocation, which
     * bypasses Spring's AOP proxy entirely, so the 5-arg method's annotation would silently not
     * apply if THIS method were the caller's actual entry point (e.g. a test calling this overload
     * directly with no transaction of its own already open) -- without an annotation here too, the
     * pessimistic lock inside the 5-arg method would be acquired and released within its own
     * separate implicit transaction before the count-check and insert even ran, providing no
     * protection at all.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConversationMember addMember(Conversation conversation, User user, GroupRole role, Long invitedByUserId) {
        return addMember(conversation, user, role, invitedByUserId, false);
    }

    /**
     * Groups Stage 2: the one race-safe primitive every membership-creating path (group creation,
     * a fresh invitation's DIRECT_ADD outcome, and GroupInvitationService#acceptInvitation) must go
     * through -- centralizing the limit/duplicate/reactivation enforcement here rather than
     * re-deriving it per call site.
     * <p>
     * {@code allowRestoreInactive} governs Stage 1.5's consent rule: a previously LEFT/REMOVED
     * (collapsed as INACTIVE -- see GroupAuthorizationService's class-level javadoc) member must
     * never be silently reactivated. {@code false} (the 4-arg overload) enforces that by rejecting
     * an INACTIVE target outright. {@code true} is reserved for the one path where the user has
     * just given exactly that consent explicitly -- accepting a fresh invitation -- and reuses
     * their existing row (deletedAt -> null) instead of inserting a duplicate
     * (conversation_id, user_id) pair, which uk_convmember_conversation_user
     * (V3__group_membership_unique_constraint.sql) would reject anyway.
     * <p>
     * <b>Race safety.</b> {@link ChatGroupRepository#findByIdForUpdate} takes a pessimistic write
     * lock on the group's chat_groups row first, before the active-member-count check, and holds
     * it for the rest of the caller's transaction (this method does not use REQUIRES_NEW -- unlike
     * ConnectionService's insert-and-recover pattern, the caller here needs this insert atomic
     * with its own surrounding writes, e.g. GroupInvitationService#acceptInvitation marking the
     * invitation ACCEPTED in the same transaction). That serializes every concurrent call to this
     * method for the SAME group (direct-add, accept, another accept) into a queue: whichever
     * transaction acquires the lock first re-reads the count and target state under it, so a
     * second, now-unblocked caller always sees the first's effects and is correctly rejected
     * (ALREADY_GROUP_MEMBER or GROUP_MEMBER_LIMIT_EXCEEDED) rather than double-inserting or
     * overshooting the cap. READ_COMMITTED (not MySQL's default REPEATABLE READ) for the same
     * reason ConversationService#createOrGetDirectConversation uses it: a plain-snapshot read taken
     * before the lock wait would miss the unblocking transaction's just-committed state. The
     * DataIntegrityViolationException catch below is a defense-in-depth backstop against the DB
     * constraint, not the primary race guard -- the lock is.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConversationMember addMember(Conversation conversation, User user, GroupRole role, Long invitedByUserId,
                                         boolean allowRestoreInactive) {
        Long conversationId = conversation.getId();

        chatGroupRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));

        GroupAuthorizationService.MembershipState state = groupAuthorizationService.resolveMembershipState(conversationId, user.getId());
        if (state == GroupAuthorizationService.MembershipState.ACTIVE_MEMBER) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_GROUP_MEMBER", "User is already a member of this group");
        }
        if (state == GroupAuthorizationService.MembershipState.INACTIVE && !allowRestoreInactive) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_REINVITATION_REQUIRED",
                    "This user previously left or was removed from the group and must be re-invited");
        }

        long activeCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(conversationId);
        if (activeCount >= GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_MEMBER_LIMIT_EXCEEDED",
                    "This group has reached the maximum of " + GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS + " members");
        }

        if (state == GroupAuthorizationService.MembershipState.INACTIVE) {
            ConversationMember restored = conversationMemberRepository.findByConversationIdAndUserId(conversationId, user.getId())
                    .orElseThrow();
            restored.setDeletedAt(null);
            restored.setRole(role);
            restored.setInvitedByUserId(invitedByUserId);
            return conversationMemberRepository.save(restored);
        }

        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        member.setInvitedByUserId(invitedByUserId);
        try {
            return conversationMemberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_GROUP_MEMBER", "User is already a member of this group");
        }
    }

    private GroupDto buildGroupDto(Conversation conversation, ChatGroup chatGroup, long activeMemberCount, GroupRole viewerRole) {
        GroupDto dto = new GroupDto();
        dto.setId(conversation.getId());
        dto.setType(conversation.getType().name());
        dto.setName(chatGroup.getName());
        dto.setDescription(chatGroup.getDescription());
        dto.setAvatarUrl(chatGroup.getAvatarUrl());
        dto.setWhoCanInvite(chatGroup.getWhoCanInvite().name());
        dto.setWhoCanSendMessages(chatGroup.getWhoCanSendMessages().name());
        dto.setWhoCanEditGroupInfo(chatGroup.getWhoCanEditGroupInfo().name());
        dto.setCreatedByUserId(chatGroup.getCreatedByUser().getId());
        dto.setCurrentUserRole(viewerRole != null ? viewerRole.name() : null);
        dto.setActiveMemberCount(activeMemberCount);
        dto.setCreatedAt(chatGroup.getCreatedAt());
        dto.setUpdatedAt(chatGroup.getUpdatedAt());
        return dto;
    }

    private String validateName(String rawName) {
        String name = rawName != null ? rawName.trim() : "";
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_NAME_REQUIRED", "Group name is required");
        }
        if (name.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_NAME_TOO_LONG", "Group name must be 100 characters or fewer");
        }
        return name;
    }

    private String validateDescription(String rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        String description = rawDescription.trim();
        if (description.isEmpty()) {
            return null;
        }
        if (description.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_DESCRIPTION_TOO_LONG", "Group description must be 500 characters or fewer");
        }
        return description;
    }
}
