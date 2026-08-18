package com.connectx.group.service;

import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.WhoCanInvite;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Groups Stage 1.5: the single authoritative place every future group operation (invitations,
 * member management, group messaging, settings changes) must go through. The frontend never
 * decides an authorization question -- it can only ask this service (directly, or via
 * GroupService/GroupController) and receive a decision or a thrown ApiException. Every method
 * here takes the actor's user id as a plain parameter, but that id must always originate from the
 * authenticated security context at the controller boundary (see GroupController's
 * {@code @AuthenticationPrincipal UserPrincipal}) -- nothing in this codebase accepts a
 * caller-supplied "currentUserId" from a request body, and no method here accepts a role as input;
 * role is always re-derived from the DB inside these methods, never trusted from a caller.
 * <p>
 * <b>Membership state.</b> ConversationMember's only lifecycle signal is {@code deletedAt}
 * (NULL = active, non-NULL = soft-deleted) -- inherited from the DIRECT-conversation "hide this
 * chat for myself" feature, which never needed to distinguish *why* a row was soft-deleted. Groups
 * do need that distinction (a user who voluntarily left a group is a very different case from one
 * an owner removed), but the schema cannot currently tell them apart: both would just be a
 * soft-deleted row with no marker of who ended the membership or why. Per Stage 1.5's explicit
 * instruction not to add schema speculatively, this is NOT solved by adding new columns/tables
 * here. Instead, {@link MembershipState} intentionally collapses "left" and "removed" into a
 * single {@code INACTIVE} state, and the one behavioral guarantee that actually matters --
 * neither case may be silently reactivated -- is enforced identically for both (see
 * {@link #evaluateAddMember}, which always resolves an INACTIVE target to
 * {@code INVITATION_REQUIRED}, never {@code DIRECT_ADD}). If a future stage needs to tell LEFT and
 * REMOVED apart for its own reasons (e.g. showing different UI copy, or letting only an
 * owner/admin re-invite a REMOVED user but anyone re-invite a LEFT one), the smallest safe
 * addition is a nullable {@code left_at} or {@code removed_by_user_id} column on
 * conversation_members, populated going forward only -- existing soft-deleted rows would remain
 * ambiguous, which is fine since this stage's rule treats them the same either way.
 * <p>
 * <b>Connection vs. membership.</b> A UserConnection row means two users are connected; it is
 * consulted here only as one input to {@link #evaluateAddMember}'s DIRECT_ADD-vs-INVITATION_REQUIRED
 * decision, exactly like {@code ConversationService#createOrGetDirectConversation}'s NOT_CONNECTED
 * gate already treats it for DIRECT chats. It never implies membership and is never used to
 * insert a ConversationMember row directly.
 */
@Service
public class GroupAuthorizationService {

    /**
     * V1 cap on ACTIVE (deletedAt IS NULL) members per group -- OWNER + ADMINs + MEMBERs combined.
     * Defined here, once, as the single source of truth every call site (GroupService today, a
     * future invite-accept flow, and the API surface GroupDto#activeMemberCount is compared
     * against) must reference rather than hardcode. Promote to a {@code @Value}-backed
     * configuration property if this ever needs to vary by deployment; nothing about the call
     * sites would need to change.
     */
    public static final int MAX_ACTIVE_GROUP_MEMBERS = 50;

    public enum MembershipState { NEVER_MEMBER, ACTIVE_MEMBER, INACTIVE }

    public enum AddMemberDecision { DIRECT_ADD, INVITATION_REQUIRED, DENIED }

    /** "Who can add me to groups?" -- see class-level javadoc on {@link #resolveGroupAddPrivacy}. */
    public enum GroupAddPrivacy { ANYONE, CONNECTIONS, NOBODY }

    public static final class AddMemberEvaluation {
        private final AddMemberDecision decision;
        private final String reasonCode;

        public AddMemberEvaluation(AddMemberDecision decision, String reasonCode) {
            this.decision = decision;
            this.reasonCode = reasonCode;
        }

        public AddMemberDecision getDecision() {
            return decision;
        }

        public String getReasonCode() {
            return reasonCode;
        }
    }

    private final ChatGroupRepository chatGroupRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserConnectionRepository userConnectionRepository;

    public GroupAuthorizationService(ChatGroupRepository chatGroupRepository,
                                      ConversationMemberRepository conversationMemberRepository,
                                      UserRepository userRepository,
                                      UserBlockRepository userBlockRepository,
                                      UserConnectionRepository userConnectionRepository) {
        this.chatGroupRepository = chatGroupRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.userConnectionRepository = userConnectionRepository;
    }

    // ==================== membership state ====================

    @Transactional(readOnly = true)
    public MembershipState resolveMembershipState(Long conversationId, Long userId) {
        return conversationMemberRepository.findByConversationIdAndUserId(conversationId, userId)
                .map(m -> m.getDeletedAt() == null ? MembershipState.ACTIVE_MEMBER : MembershipState.INACTIVE)
                .orElse(MembershipState.NEVER_MEMBER);
    }

    // ==================== hard gates (throw ApiException) ====================

    /**
     * The one check every group-scoped read/write must start with: the group exists, its
     * conversation is actually type GROUP (not e.g. a DIRECT id passed by mistake), and the caller
     * is a currently-active member. Used by GroupService#getGroupDetails/getGroupMembers today;
     * every future group endpoint (messaging, settings, member management) must call this too
     * rather than re-deriving the same three checks.
     */
    @Transactional(readOnly = true)
    public ChatGroup requireActiveMember(Long userId, Long groupId) {
        ChatGroup chatGroup = chatGroupRepository.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));

        if (chatGroup.getConversation().getType() != ConversationType.GROUP) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found");
        }

        if (resolveMembershipState(groupId, userId) != MembershipState.ACTIVE_MEMBER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER", "You are not a member of this group");
        }

        return chatGroup;
    }

    /** The caller's own current role, re-derived from the DB -- never accepted as a parameter. */
    @Transactional(readOnly = true)
    public GroupRole requireRole(Long userId, Long groupId) {
        requireActiveMember(userId, groupId);
        return conversationMemberRepository.findByConversationIdAndUserId(groupId, userId)
                .map(ConversationMember::getRole)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER", "You are not a member of this group"));
    }

    // @Transactional here (not just on requireRole/requireActiveMember below) matters: this method
    // calls requireRole via a plain "this." self-invocation, which bypasses Spring's transactional
    // proxy entirely, so requireRole's own annotation would not open a session on its own when
    // this is the externally-called entry point. The outer @Transactional here is what actually
    // opens the Hibernate session that the lazy Conversation association inside requireActiveMember
    // needs.
    @Transactional(readOnly = true)
    public void requireOwner(Long userId, Long groupId) {
        if (requireRole(userId, groupId) != GroupRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the group owner can perform this action");
        }
    }

    @Transactional(readOnly = true)
    public void requireAdminOrOwner(Long userId, Long groupId) {
        GroupRole role = requireRole(userId, groupId);
        if (role != GroupRole.OWNER && role != GroupRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_OR_OWNER_ONLY", "Only a group admin or owner can perform this action");
        }
    }

    // ==================== non-throwing checks ====================

    @Transactional(readOnly = true)
    public boolean canViewGroup(Long userId, Long groupId) {
        return resolveMembershipState(groupId, userId) == MembershipState.ACTIVE_MEMBER;
    }

    /** V1: any active member may send; there is no mute/restricted-posting mode yet. */
    @Transactional(readOnly = true)
    public boolean canSendMessages(Long userId, Long groupId) {
        return resolveMembershipState(groupId, userId) == MembershipState.ACTIVE_MEMBER;
    }

    @Transactional(readOnly = true)
    public boolean canManageMembers(Long userId, Long groupId) {
        return hasActiveRole(userId, groupId, GroupRole.OWNER, GroupRole.ADMIN);
    }

    @Transactional(readOnly = true)
    public boolean canInvite(Long actorUserId, Long groupId) {
        ChatGroup chatGroup = chatGroupRepository.findById(groupId).orElse(null);
        if (chatGroup == null) {
            return false;
        }
        GroupRole role = activeRoleOrNull(actorUserId, groupId);
        if (role == null) {
            return false;
        }
        if (role == GroupRole.OWNER || role == GroupRole.ADMIN) {
            return true;
        }
        return chatGroup.getWhoCanInvite() == WhoCanInvite.ALL_MEMBERS;
    }

    @Transactional(readOnly = true)
    public boolean isGroupFull(Long groupId) {
        return conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId) >= MAX_ACTIVE_GROUP_MEMBERS;
    }

    private boolean hasActiveRole(Long userId, Long groupId, GroupRole... allowed) {
        GroupRole role = activeRoleOrNull(userId, groupId);
        if (role == null) {
            return false;
        }
        for (GroupRole candidate : allowed) {
            if (candidate == role) {
                return true;
            }
        }
        return false;
    }

    private GroupRole activeRoleOrNull(Long userId, Long groupId) {
        return conversationMemberRepository.findByConversationIdAndUserId(groupId, userId)
                .filter(m -> m.getDeletedAt() == null)
                .map(ConversationMember::getRole)
                .orElse(null);
    }

    // ==================== target-user (add/invite) evaluation ====================

    /**
     * Evaluates whether {@code actorUserId} adding {@code targetUserId} to {@code groupId} should
     * be a silent {@code DIRECT_ADD}, require an explicit {@code INVITATION_REQUIRED} accept step,
     * or be flatly {@code DENIED} -- without performing any write. Stage 1.5 establishes this
     * decision model only; no invitation persistence exists yet (deferred to Stage 2), and
     * GroupService#addMember is not wired to any endpoint that calls this. Order matters and
     * mirrors this codebase's existing precedence conventions (see ProfileVisibilityService's
     * javadoc: "a block always wins, checked first").
     */
    @Transactional(readOnly = true)
    public AddMemberEvaluation evaluateAddMember(Long actorUserId, Long groupId, Long targetUserId) {
        requireActiveMember(actorUserId, groupId);

        if (actorUserId.equals(targetUserId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "CANNOT_ADD_SELF");
        }
        if (!canInvite(actorUserId, groupId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "NO_INVITE_PERMISSION");
        }
        if (userBlockRepository.existsEitherDirection(actorUserId, targetUserId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "BLOCKED");
        }

        MembershipState targetState = resolveMembershipState(groupId, targetUserId);
        if (targetState == MembershipState.ACTIVE_MEMBER) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "ALREADY_MEMBER");
        }
        if (targetState == MembershipState.INACTIVE) {
            // Consent rule: a user who left or was removed must never be silently reactivated --
            // see class-level javadoc on the LEFT/REMOVED collapse. Always INVITATION_REQUIRED,
            // regardless of connection status or the target's privacy setting below.
            return new AddMemberEvaluation(AddMemberDecision.INVITATION_REQUIRED, "REQUIRES_REINVITATION");
        }

        if (isGroupFull(groupId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "GROUP_FULL");
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));
        if (resolveGroupAddPrivacy(target) == GroupAddPrivacy.NOBODY) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "TARGET_PRIVACY_NOBODY");
        }

        if (!isConnected(actorUserId, targetUserId)) {
            // Not connected -- connection is a precondition for a *silent* add, not for an
            // invitation; the actor can still send one for the target to explicitly accept.
            return new AddMemberEvaluation(AddMemberDecision.INVITATION_REQUIRED, "NOT_CONNECTED");
        }

        return new AddMemberEvaluation(AddMemberDecision.DIRECT_ADD, "CONNECTED");
    }

    private boolean isConnected(Long userId1, Long userId2) {
        Long low = Math.min(userId1, userId2);
        Long high = Math.max(userId1, userId2);
        return userConnectionRepository.existsByUserLowIdAndUserHighId(low, high);
    }

    /**
     * "Who can add me to groups?" is not yet a persisted user setting or a UI (explicitly out of
     * scope for Stage 1.5) -- this returns the default (ANYONE) unconditionally so
     * {@link #evaluateAddMember} has a real slot to call once a settings column exists, instead of
     * inlining a TODO into the decision method itself. Per the Stage 1.5 spec, ANYONE only means
     * the user's own preference does not additionally restrict an otherwise-authorized action; it
     * does not bypass canInvite, blocking, membership state, or the capacity check above.
     */
    private GroupAddPrivacy resolveGroupAddPrivacy(User target) {
        return GroupAddPrivacy.ANYONE;
    }
}
