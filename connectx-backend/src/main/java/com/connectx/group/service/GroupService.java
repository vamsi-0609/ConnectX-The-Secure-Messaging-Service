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
import com.connectx.group.dto.UpdateGroupInfoRequestDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.WhoCanEditGroupInfo;
import com.connectx.group.entity.WhoCanInvite;
import com.connectx.group.entity.WhoCanSendMessages;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.group.storage.GroupImageStorage;
import com.connectx.common.util.AfterCommitExecutor;
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
import org.springframework.web.multipart.MultipartFile;

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
    private final AfterCommitExecutor afterCommitExecutor;
    private final GroupImageStorage groupImageStorage;

    public GroupService(ConversationRepository conversationRepository,
                         ConversationMemberRepository conversationMemberRepository,
                         ChatGroupRepository chatGroupRepository,
                         GroupInvitationRepository groupInvitationRepository,
                         UserRepository userRepository,
                         ProfileVisibilityService profileVisibilityService,
                         GroupAuthorizationService groupAuthorizationService,
                         SimpMessagingTemplate messagingTemplate,
                         AfterCommitExecutor afterCommitExecutor,
                         GroupImageStorage groupImageStorage) {
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.groupInvitationRepository = groupInvitationRepository;
        this.userRepository = userRepository;
        this.profileVisibilityService = profileVisibilityService;
        this.groupAuthorizationService = groupAuthorizationService;
        this.messagingTemplate = messagingTemplate;
        this.afterCommitExecutor = afterCommitExecutor;
        this.groupImageStorage = groupImageStorage;
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
        notifyGroupInfoChanged(groupId);

        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, GroupRole.OWNER);
    }

    /**
     * Editable group "profile" fields (name/description) -- gated by who_can_edit_group_info
     * (requireCanEditGroupInfo: OWNER/ADMIN or ALL_MEMBERS depending on the group's own setting),
     * deliberately NOT requireOwner -- unlike the three policy ENUMs in updateSettings, which only
     * the owner may ever change, "who may edit the group's name/description/photo" is itself
     * configurable per-group and already fully enforced by requireCanEditGroupInfo (the same check
     * uploadAvatar/removeAvatar use, since a group's name/description are exactly as much "info" as
     * its avatar). Both fields optional and independently validated/applied, all within one
     * transaction, mirroring updateSettings' all-or-nothing contract.
     */
    @Transactional
    public GroupDto updateGroupInfo(Long actorUserId, Long groupId, UpdateGroupInfoRequestDto dto) {
        ChatGroup chatGroup = groupAuthorizationService.requireCanEditGroupInfo(actorUserId, groupId);

        if (dto.getName() != null) {
            chatGroup.setName(validateName(dto.getName()));
        }
        if (dto.getDescription() != null) {
            chatGroup.setDescription(validateDescription(dto.getDescription()));
        }

        chatGroupRepository.save(chatGroup);
        notifyGroupInfoChanged(groupId);

        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        GroupRole viewerRole = conversationMemberRepository.findByConversationIdAndUserId(groupId, actorUserId)
                .map(ConversationMember::getRole)
                .orElse(null);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, viewerRole);
    }

    /**
     * Tells every currently-active member's already-open client to re-fetch this group's info --
     * the counterpart to {@link #markKeyRotationRequired} for non-key-material changes (settings,
     * avatar). Without this, a member with the group open when who_can_send_messages flips to
     * ADMINS_ONLY keeps seeing (and being able to type into) the real composer until they happen
     * to reload; the server-side send check was never bypassed by this (MessageService
     * re-validates authoritatively on every send), but the client stayed stale and confusing --
     * found via live multi-user testing switching a setting while another member had the group
     * open. Deferred until commit for the identical reason markKeyRotationRequired's notification
     * is: a receiver's immediate refetch must not race the still-in-flight UPDATE.
     */
    private void notifyGroupInfoChanged(Long groupId) {
        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent infoChangedEvent = WsEvent.of("GROUP_INFO_UPDATED", Map.of("conversationId", groupId));
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", infoChangedEvent);
            }
        });
    }

    /**
     * Groups Stage 5B: tells every currently-active member's already-open client that a role
     * changed (promote/demote via {@link #changeRole}, or ownership transfer via
     * {@link #transferOwnership}) -- same shape and rationale as {@link #notifyGroupInfoChanged},
     * just a distinct event type so the frontend isn't forced to conflate "a role changed" with
     * "settings/avatar/name changed" the way reusing GROUP_INFO_UPDATED would. Deliberately
     * includes the acting user's own username in the recipient list (unlike
     * {@link #markKeyRotationRequired}'s membership-confidentiality exclusion, which does not apply
     * here): a role/ownership change is not confidentiality-relevant, and the actor's *other*
     * devices/tabs still need the same notification a second party would get, since this method
     * carries no assumption that the caller's own session is the only one open for that account.
     * No key rotation, no key-material payload -- role/ownership changes never affect who can
     * decrypt (see {@link #changeRole} and {@link #transferOwnership}'s own javadoc), so this is
     * purely a UI/authorization-state synchronization signal.
     */
    private void notifyGroupRoleChanged(Long groupId, Long affectedUserId, Long changedByUserId) {
        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent roleChangedEvent = WsEvent.of("GROUP_ROLE_CHANGED", Map.of(
                    "conversationId", groupId,
                    "affectedUserId", affectedUserId,
                    "changedByUserId", changedByUserId
            ));
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", roleChangedEvent);
            }
        });
    }

    /**
     * Groups Stage 5D: explicit membership-domain notification, distinct from
     * {@link #markKeyRotationRequired}'s cryptographic-synchronization signal -- both fire for the
     * same join, but this one exists purely so an already-open Members screen can react to "someone
     * joined" without having to infer it from a key-rotation notice. Deliberately excludes the
     * newly added member themselves: their own client already has everything it needs from the
     * existing GROUP_KEY_ROTATION_REQUIRED + Phase 5C conversation-sync path (which they also
     * receive, since markKeyRotationRequired's active-member query runs after this same addMember
     * call), so sending them a second, redundant event here would add nothing. Package-visible: called
     * from GroupInvitationService (direct-add and invitation-accept), the only two paths that add a
     * member via an actual join rather than group creation -- see the two call sites' own comments for
     * why {@link #createGroup}'s owner-membership insert must NOT trigger this (there is no one else
     * in the group yet to notify, and the owner's own initial membership is the group's starting
     * state, not a "join" event, mirroring markKeyRotationRequired's identical exclusion).
     */
    void notifyMemberAdded(Long groupId, Long addedUserId) {
        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null && !m.getUser().getId().equals(addedUserId))
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent memberAddedEvent = WsEvent.of("GROUP_MEMBER_ADDED",
                    Map.of("conversationId", groupId, "memberId", addedUserId));
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", memberAddedEvent);
            }
        });
    }

    /**
     * Groups Stage 5D: the membership-domain counterpart to {@link #notifyMemberAdded}, for removal
     * and voluntary leave (both go through {@link #endMembership}, so this is added there once
     * rather than duplicated in removeMember/leaveGroup). SECURITY-CRITICAL: the removed/departed
     * user must never receive this event -- endMembership always sets the member's deletedAt before
     * calling this, and this method's active-member query (the same
     * findByConversationIdAndDeletedAtIsNullWithUsers already used by
     * {@link #markKeyRotationRequired}, which has excluded a departed member this same way since
     * Stage 3) naturally excludes them as a result. This is not a separate check to remember to keep
     * in sync -- it is the same exclusion mechanism the codebase already relies on for
     * GROUP_KEY_ROTATION_REQUIRED, reused unmodified.
     */
    private void notifyMemberRemoved(Long groupId, Long removedUserId) {
        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent memberRemovedEvent = WsEvent.of("GROUP_MEMBER_REMOVED",
                    Map.of("conversationId", groupId, "memberId", removedUserId));
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", memberRemovedEvent);
            }
        });
    }

    /**
     * Group photo upload. Gated by {@code who_can_edit_group_info} (requireCanEditGroupInfo),
     * never a hardcoded owner-only rule -- a group's avatar is part of its "info" exactly like
     * name/description would be. Reuses GroupImageStorage (a completely separate storage root and
     * URL namespace from the per-user ProfileImageStorage) so a group avatar can never resolve to,
     * or overwrite, a user's profile photo. Cache-busts with a query-string timestamp, mirroring
     * UserService#uploadProfilePhoto, so a client that already cached the old avatar URL (e.g. the
     * conversation list, fetched before this upload) is forced to refetch once it receives the new
     * URL over WS/HTTP rather than silently keeping a stale image.
     */
    @Transactional
    public GroupDto uploadAvatar(Long actorUserId, Long groupId, MultipartFile file) {
        groupAuthorizationService.requireCanEditGroupInfo(actorUserId, groupId);

        ChatGroup chatGroup = chatGroupRepository.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));

        String publicPath = groupImageStorage.store(groupId, file);
        chatGroup.setAvatarUrl(publicPath + "?v=" + System.currentTimeMillis());
        chatGroupRepository.save(chatGroup);
        notifyGroupInfoChanged(groupId);

        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        GroupRole viewerRole = conversationMemberRepository.findByConversationIdAndUserId(groupId, actorUserId)
                .map(ConversationMember::getRole)
                .orElse(null);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, viewerRole);
    }

    @Transactional
    public GroupDto removeAvatar(Long actorUserId, Long groupId) {
        groupAuthorizationService.requireCanEditGroupInfo(actorUserId, groupId);

        ChatGroup chatGroup = chatGroupRepository.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));

        groupImageStorage.delete(groupId);
        chatGroup.setAvatarUrl(null);
        chatGroupRepository.save(chatGroup);
        notifyGroupInfoChanged(groupId);

        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        GroupRole viewerRole = conversationMemberRepository.findByConversationIdAndUserId(groupId, actorUserId)
                .map(ConversationMember::getRole)
                .orElse(null);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, viewerRole);
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
        notifyGroupRoleChanged(groupId, targetUserId, actorUserId);

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
        // Confidentiality-relevant membership change (removal or voluntary leave) -- the shared
        // group key must rotate so this now-departed member cannot decrypt any future message. See
        // markKeyRotationRequired's own javadoc for the full rationale.
        markKeyRotationRequired(groupId);
        // Membership-domain notification (Stage 5D) for the remaining members' UI -- see
        // notifyMemberRemoved's own javadoc for why the departed user is safely excluded.
        notifyMemberRemoved(groupId, userId);
        // Phase 5E: private multi-session revocation signal for the departed user's own account --
        // see notifyAccessRevoked's own javadoc for why this can't just be notifyMemberRemoved
        // re-targeted at them.
        notifyAccessRevoked(groupId, userId);
    }

    /**
     * Groups Phase 5E: private counterpart to {@link #notifyMemberRemoved}, for the one recipient
     * that method deliberately excludes -- the user whose own membership just ended (removal or
     * voluntary leave, both via {@link #endMembership}). Exists because a user can have more than
     * one active session (multiple tabs/devices); only the session that made the REST call (if any --
     * a removal is made by the *other* party, so the removed user's sessions never make one at all)
     * learns about the change locally, leaving any other open session with stale local group state
     * until it next happens to reconcile. This event carries no membership-list data -- it is purely
     * a "go invalidate your own local copy of this group" signal, distinct in kind from
     * GROUP_MEMBER_REMOVED (a membership-roster notice for members who are still active) and from
     * GROUP_KEY_ROTATION_REQUIRED (a cryptographic-sync signal every remaining member also gets).
     * Deliberately sent to ONLY the departed user's own queue -- never broadcast, never containing
     * key material -- via the same convertAndSendToUser mechanism already used for every other
     * per-user WS notification in this class, so no new delivery path is introduced. Ordering
     * against GROUP_KEY_ROTATION_REQUIRED is intentionally not guaranteed: the frontend handler for
     * this event only discards locally-cached UI state, never resolves or mints a group key, so it
     * is safe regardless of which of the two events a client happens to process first.
     */
    private void notifyAccessRevoked(Long groupId, Long revokedUserId) {
        String username = userRepository.findById(revokedUserId)
                .map(User::getUsername)
                .orElse(null);
        if (username == null) {
            return;
        }
        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent accessRevokedEvent = WsEvent.of("GROUP_ACCESS_REVOKED",
                    Map.of("conversationId", groupId));
            messagingTemplate.convertAndSendToUser(username, "/queue/messages", accessRevokedEvent);
        });
    }

    /**
     * Groups E2EE messaging stage: advances the group's authoritative key version whenever a
     * membership event (join, removal, or leave) requires the shared group key to rotate --
     * confidentiality-relevant events only. Deliberately NOT called from createGroup (the owner's
     * initial membership is the group's starting state, not a rotation) or changeRole (a role
     * change never affects who can decrypt -- see docs on the key lifecycle). Does not generate,
     * wrap, or distribute the new key itself -- an authorized active client does that afterward
     * (GroupKeyService#submitWrappedKey), reacting to seeing its own wrapped key's version fall
     * behind this counter, or to the notification broadcast below. Locks the chat_groups row first
     * (findByIdForUpdate, already used by addMember for the identical reason) so concurrent
     * membership changes for the same group serialize their version bumps rather than losing one
     * under a plain read-modify-write.
     * <p>
     * Package-visible: called from GroupInvitationService (join events) as well as this class
     * (removal/leave via endMembership), both in {@code com.connectx.group.service}.
     * <p>
     * Notifies every remaining ACTIVE member's personal queue (never the group's
     * {@code /topic/conversation/{id}} topic -- see the Part 15 WebSocket-delivery fix elsewhere in
     * this stage: a departed member's still-open browser tab must not learn anything further about
     * this group, even a content-free notice) so any currently-open client can self-elect as
     * rotator immediately rather than waiting for someone to next open the group. Purely a liveness
     * optimization -- the safety property (no send/read ever succeeds under a stale key) holds
     * regardless of whether anyone is listening, since every group message send is rejected
     * server-side unless its groupKeyVersion matches this counter exactly (MessageService).
     */
    void markKeyRotationRequired(Long groupId) {
        ChatGroup chatGroup = chatGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));
        chatGroup.setKeyVersion(chatGroup.getKeyVersion() + 1);
        chatGroupRepository.save(chatGroup);
        int newVersion = chatGroup.getKeyVersion();

        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        // Deliberately deferred until the surrounding transaction actually COMMITS (matching
        // MessageService's established convention for every WS broadcast it sends) rather than
        // fired synchronously mid-transaction: the whole point of this notification is "go
        // re-fetch the group, its keyVersion just changed" -- sending it before the UPDATE is even
        // flushed/committed means a receiving client's immediate re-fetch can race the write and
        // observe the OLD version, silently discarding the notification's entire purpose. Found via
        // live multi-user testing: the sender's own client kept re-reading a stale keyVersion and
        // failing every group-message send with GROUP_KEY_VERSION_MISMATCH even after retrying,
        // because the "rotation happened, go refetch" signal always arrived before the row was
        // actually committed.
        afterCommitExecutor.runAfterCommit(() -> {
            WsEvent rotationEvent = WsEvent.of("GROUP_KEY_ROTATION_REQUIRED",
                    Map.of("conversationId", groupId, "keyVersion", newVersion));
            for (String username : usernames) {
                messagingTemplate.convertAndSendToUser(username, "/queue/messages", rotationEvent);
            }
        });
    }

    /**
     * Groups E2EE messaging stage: OWNER-only ownership transfer. The previous OWNER becomes
     * ADMIN (not demoted to MEMBER -- they were already trusted at OWNER level a moment ago, and
     * ADMIN is the closer landing role, matching how promote/demote already treats ADMIN as the
     * senior non-owner role). No key rotation: the new owner is already an ACTIVE member and
     * already holds the current group key (ownership is a permission change, not a membership
     * change -- see docs on the key lifecycle, same principle as changeRole never rotating).
     */
    @Transactional
    public void transferOwnership(Long actorUserId, Long groupId, Long newOwnerUserId) {
        groupAuthorizationService.requireCanTransferOwnership(actorUserId, groupId, newOwnerUserId);

        ConversationMember currentOwner = conversationMemberRepository.findByConversationIdAndUserId(groupId, actorUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "You are not a member of this group"));
        ConversationMember newOwner = conversationMemberRepository.findByConversationIdAndUserId(groupId, newOwnerUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "Target user is not an active member of this group"));

        currentOwner.setRole(GroupRole.ADMIN);
        newOwner.setRole(GroupRole.OWNER);
        conversationMemberRepository.save(currentOwner);
        conversationMemberRepository.save(newOwner);
        notifyGroupRoleChanged(groupId, newOwnerUserId, actorUserId);
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
        dto.setKeyVersion(chatGroup.getKeyVersion());
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
