package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.GroupInvitationDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.ProfileVisibilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Groups Stage 2: invitations and safe membership creation. Every authorization question here --
 * actor membership/role/permission, target membership state, capacity, blocking, connection,
 * target privacy -- is decided by GroupAuthorizationService (via
 * {@link GroupAuthorizationService#evaluateAddMember}, established in Stage 1.5); this service
 * only orchestrates persistence around that decision. It never re-derives an authorization
 * decision itself.
 */
@Service
public class GroupInvitationService {

    private static final Logger log = LoggerFactory.getLogger(GroupInvitationService.class);

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupService groupService;
    private final GroupInvitationRepository groupInvitationRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final ProfileVisibilityService profileVisibilityService;
    // Self-injected proxy so insertPendingInvitationInNewTransaction's REQUIRES_NEW actually runs
    // through Spring's transactional AOP proxy when invoked from createInvitation below -- same
    // pattern and rationale as ConnectionService#self (see that field's javadoc): a duplicate
    // PENDING-pair insert must run in a genuinely separate transaction for the caller to recover
    // from the lost race cleanly, since catching DataIntegrityViolationException from a plain
    // insert in the SAME transaction would still leave that transaction rollback-only.
    private final GroupInvitationService self;

    public GroupInvitationService(GroupAuthorizationService groupAuthorizationService,
                                   GroupService groupService,
                                   GroupInvitationRepository groupInvitationRepository,
                                   ChatGroupRepository chatGroupRepository,
                                   ConversationRepository conversationRepository,
                                   UserRepository userRepository,
                                   ProfileVisibilityService profileVisibilityService,
                                   @Lazy GroupInvitationService self) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupService = groupService;
        this.groupInvitationRepository = groupInvitationRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.profileVisibilityService = profileVisibilityService;
        this.self = self;
    }

    // ==================== create (invite or direct-add) ====================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateGroupInvitationResponseDto createInvitation(Long actorUserId, Long groupId, CreateGroupInvitationRequestDto dto) {
        Long targetUserId = dto.getTargetUserId();

        // requireActiveMember (inside evaluateAddMember) already covers: group exists, group is
        // actually type GROUP, actor is an active member. evaluateAddMember additionally covers
        // actor role/permission (WHO_CAN_INVITE), target existence, target membership state
        // (including the LEFT/REMOVED consent rule), capacity, blocking, and target privacy --
        // this method makes no authorization decision of its own, it only acts on the result.
        GroupAuthorizationService.AddMemberEvaluation evaluation =
                groupAuthorizationService.evaluateAddMember(actorUserId, groupId, targetUserId);

        if (evaluation.getDecision() == GroupAuthorizationService.AddMemberDecision.DENIED) {
            throw denialFor(evaluation.getReasonCode());
        }

        Conversation conversation = conversationRepository.getReferenceById(groupId);
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));

        if (evaluation.getDecision() == GroupAuthorizationService.AddMemberDecision.DIRECT_ADD) {
            // Only reachable when evaluateAddMember has already confirmed the target is
            // NEVER_MEMBER, connected to the actor, and the target's (currently-default) privacy
            // does not forbid it -- see that method's javadoc. Never restores an inactive row (the
            // 4-arg overload), which is structurally impossible to hit here anyway since
            // evaluateAddMember never returns DIRECT_ADD for an INACTIVE target.
            groupService.addMember(conversation, target, GroupRole.MEMBER, actorUserId);
            log.info("Group direct-add: groupId={}, actorUserId={}, targetUserId={}", groupId, actorUserId, targetUserId);
            return new CreateGroupInvitationResponseDto("DIRECT_ADDED", null);
        }

        // INVITATION_REQUIRED
        User actor = userRepository.getReferenceById(actorUserId);
        GroupInvitation invitation;
        try {
            invitation = self.insertPendingInvitationInNewTransaction(conversation, target, actor);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate pending group invitation lost race: groupId={}, targetUserId={}", groupId, targetUserId);
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ALREADY_PENDING",
                    "A pending invitation already exists for this user in this group");
        }
        log.info("Group invitation created: groupId={}, actorUserId={}, targetUserId={}, invitationId={}",
                groupId, actorUserId, targetUserId, invitation.getId());
        return new CreateGroupInvitationResponseDto("INVITATION_SENT", toDto(invitation, actorUserId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GroupInvitation insertPendingInvitationInNewTransaction(Conversation group, User invitee, User invitedBy) {
        return groupInvitationRepository.saveAndFlush(new GroupInvitation(group, invitee, invitedBy));
    }

    private ApiException denialFor(String reasonCode) {
        return switch (reasonCode) {
            case "CANNOT_ADD_SELF" -> new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_ADD_SELF", "You cannot invite yourself");
            case "NO_INVITE_PERMISSION" -> new ApiException(HttpStatus.FORBIDDEN, "NO_INVITE_PERMISSION", "You do not have permission to invite members to this group");
            case "BLOCKED" -> new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "This action is not permitted");
            case "ALREADY_MEMBER" -> new ApiException(HttpStatus.CONFLICT, "ALREADY_GROUP_MEMBER", "User is already a member of this group");
            case "GROUP_FULL" -> new ApiException(HttpStatus.CONFLICT, "GROUP_MEMBER_LIMIT_EXCEEDED",
                    "This group has reached the maximum of " + GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS + " members");
            case "TARGET_PRIVACY_NOBODY" -> new ApiException(HttpStatus.FORBIDDEN, "TARGET_PRIVACY_NOBODY", "This action is not permitted");
            case "TARGET_PRIVACY_CONNECTIONS_ONLY" -> new ApiException(HttpStatus.FORBIDDEN, "TARGET_PRIVACY_CONNECTIONS_ONLY", "This action is not permitted");
            default -> new ApiException(HttpStatus.FORBIDDEN, "DENIED", "This action is not permitted");
        };
    }

    // ==================== accept / reject / cancel ====================

    /**
     * Race safety: groupService.addMember (5-arg, allowRestoreInactive=true) acquires a
     * pessimistic write lock on the group's chat_groups row before re-checking capacity and
     * target state, and this whole method runs in one transaction spanning both that call and the
     * subsequent "mark ACCEPTED" write -- so a concurrent second accept of a *different* invitation
     * for the same target+group, or a concurrent direct-add, correctly serializes against this one
     * and is rejected (ALREADY_GROUP_MEMBER) rather than double-inserting or exceeding the cap. A
     * concurrent second accept of the *same* invitation is additionally caught by the PENDING
     * re-check below racing against this transaction's own status update.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GroupInvitationDto acceptInvitation(Long currentUserId, Long invitationId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation not found"));

        if (!invitation.getInvitee().getId().equals(currentUserId)) {
            // Mirrors ConnectionService#acceptRequest's precedent for the identical shape of
            // check: a generic FORBIDDEN, not a distinct "not yours" message.
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the invited user can accept this invitation");
        }
        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            // Covers rejected/cancelled/already-accepted uniformly -- including a duplicate accept
            // of the same invitation, which is therefore safe: no second membership row, no
            // exception beyond this clean, typed conflict.
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", "This invitation is no longer pending");
        }

        Long groupId = invitation.getGroup().getId();
        Long inviterId = invitation.getInvitedBy().getId();

        // Re-check blocking at acceptance time -- either party could have blocked the other since
        // the invitation was sent; the state captured at creation time is not assumed still valid.
        if (groupAuthorizationService.isBlockedEitherDirection(currentUserId, inviterId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "This invitation can no longer be accepted");
        }

        Conversation conversation = conversationRepository.getReferenceById(groupId);
        User invitee = invitation.getInvitee();
        // allowRestoreInactive=true: this call site is the one place Stage 1.5's consent rule
        // permits reactivating a previously LEFT/REMOVED member -- the user just gave that exact
        // consent by explicitly accepting a fresh invitation. Re-checks capacity and target state
        // itself, under its own pessimistic lock (see that method's javadoc) -- not re-derived here.
        groupService.addMember(conversation, invitee, GroupRole.MEMBER, inviterId, true);

        invitation.setStatus(GroupInvitationStatus.ACCEPTED);
        invitation.setRespondedAt(Instant.now());
        groupInvitationRepository.save(invitation);

        log.info("Group invitation accepted: invitationId={}, groupId={}, userId={}", invitationId, groupId, currentUserId);
        return toDto(invitation, currentUserId);
    }

    @Transactional
    public GroupInvitationDto rejectInvitation(Long currentUserId, Long invitationId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation not found"));

        if (!invitation.getInvitee().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the invited user can reject this invitation");
        }
        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", "This invitation is no longer pending");
        }

        invitation.setStatus(GroupInvitationStatus.REJECTED);
        invitation.setRespondedAt(Instant.now());
        groupInvitationRepository.save(invitation);

        log.info("Group invitation rejected: invitationId={}, userId={}", invitationId, currentUserId);
        return toDto(invitation, currentUserId);
    }

    @Transactional
    public GroupInvitationDto cancelInvitation(Long currentUserId, Long invitationId) {
        GroupInvitation invitation = groupInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "Invitation not found"));

        if (!invitation.getInvitedBy().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the inviter can cancel this invitation");
        }
        // "inviter must still be authorized to manage invitations" -- re-checked at cancel time,
        // not assumed still true from when the invitation was created (e.g. the inviter could have
        // since been demoted, or WHO_CAN_INVITE could have changed to OWNER_ADMIN_ONLY).
        Long groupId = invitation.getGroup().getId();
        if (!groupAuthorizationService.canInvite(currentUserId, groupId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_INVITE_PERMISSION", "You no longer have permission to manage invitations for this group");
        }
        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_NOT_PENDING", "This invitation is no longer pending");
        }

        invitation.setStatus(GroupInvitationStatus.CANCELLED);
        invitation.setRespondedAt(Instant.now());
        groupInvitationRepository.save(invitation);

        log.info("Group invitation cancelled: invitationId={}, userId={}", invitationId, currentUserId);
        return toDto(invitation, currentUserId);
    }

    // ==================== listing (always scoped to the caller) ====================

    @Transactional(readOnly = true)
    public List<GroupInvitationDto> getReceivedPendingInvitations(Long currentUserId) {
        List<GroupInvitation> invitations = groupInvitationRepository
                .findByInviteeIdAndStatusWithUsers(currentUserId, GroupInvitationStatus.PENDING);
        return toDtos(invitations, currentUserId);
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationDto> getSentPendingInvitations(Long currentUserId) {
        List<GroupInvitation> invitations = groupInvitationRepository
                .findByInvitedByIdAndStatusWithUsers(currentUserId, GroupInvitationStatus.PENDING);
        return toDtos(invitations, currentUserId);
    }

    // ==================== DTO assembly ====================

    private GroupInvitationDto toDto(GroupInvitation invitation, Long viewerId) {
        String groupName = chatGroupRepository.findById(invitation.getGroup().getId())
                .map(ChatGroup::getName)
                .orElse(null);
        boolean inviteePhotoVisible = profileVisibilityService.isProfilePhotoVisible(invitation.getInvitee(), viewerId);
        boolean invitedByPhotoVisible = profileVisibilityService.isProfilePhotoVisible(invitation.getInvitedBy(), viewerId);
        return GroupInvitationDto.fromEntity(invitation, groupName, inviteePhotoVisible, invitedByPhotoVisible);
    }

    private List<GroupInvitationDto> toDtos(List<GroupInvitation> invitations, Long viewerId) {
        if (invitations.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = invitations.stream().map(gi -> gi.getGroup().getId()).distinct().collect(Collectors.toList());
        Map<Long, String> groupNameById = chatGroupRepository.findAllById(groupIds).stream()
                .collect(Collectors.toMap(ChatGroup::getConversationId, ChatGroup::getName));

        List<User> photoOwners = invitations.stream()
                .flatMap(gi -> java.util.stream.Stream.of(gi.getInvitee(), gi.getInvitedBy()))
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Boolean> photoVisibilityByUserId = profileVisibilityService.resolvePhotoVisibility(viewerId, photoOwners);

        return invitations.stream()
                .map(gi -> GroupInvitationDto.fromEntity(
                        gi,
                        groupNameById.get(gi.getGroup().getId()),
                        photoVisibilityByUserId.getOrDefault(gi.getInvitee().getId(), false),
                        photoVisibilityByUserId.getOrDefault(gi.getInvitedBy().getId(), false)))
                .collect(Collectors.toList());
    }
}
