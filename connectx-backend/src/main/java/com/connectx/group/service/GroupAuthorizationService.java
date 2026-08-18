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
import com.connectx.user.entity.GroupAddPrivacy;
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

    /**
     * Exposed so callers orchestrating a multi-step flow (e.g. GroupInvitationService re-checking
     * blocking at acceptance time, since either party could have blocked the other after the
     * invitation was sent) go through this service rather than reaching into
     * UserBlockRepository directly -- GroupAuthorizationService stays the single authority for
     * every blocking-relevant decision in the group domain, per Stage 1.5/2's mandate.
     */
    @Transactional(readOnly = true)
    public boolean isBlockedEitherDirection(Long userId1, Long userId2) {
        return userBlockRepository.existsEitherDirection(userId1, userId2);
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
     * or be flatly {@code DENIED} -- without performing any write. No invitation persistence
     * happens here (see {@code GroupInvitationService}, which acts on this result); this method
     * only decides.
     * <p>
     * Precedence (docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md's Stage 4 checkpoint has the full
     * rationale for why this exact order, and for the two corrections Stage 4 made to the original
     * Stage 1.5/2 version -- role/permission split into a coarse gate (3) and a final,
     * connection-aware confirmation (9), and CONNECTIONS privacy added as a target-side veto):
     * <ol>
     * <li>group validity + actor active membership ({@link #requireActiveMember})</li>
     * <li>(self-add short-circuit)</li>
     * <li>actor role/permission -- coarse gate: OWNER/ADMIN always pass; MEMBER passes only if
     * {@code who_can_invite = ALL_MEMBERS} (docs/CONNECTX_GROUP_ARCHITECTURE.md §6/§7)</li>
     * <li>blocking, either direction -- always wins, same precedence convention as
     * {@code ProfileVisibilityService}</li>
     * <li>target membership state -- INACTIVE (LEFT/REMOVED, collapsed -- see class javadoc)
     * always resolves to INVITATION_REQUIRED here, never DIRECT_ADD, regardless of every check
     * below; this is the one rule privacy/policy/connection can never override</li>
     * <li>group capacity</li>
     * <li>target's persisted group-add privacy ({@code User#getGroupAddPrivacy}) -- NOBODY denies
     * outright</li>
     * <li>connection relationship</li>
     * <li>group invitation policy, finalized -- CONNECTIONS privacy denies outright if not
     * connected (a target-side veto even an OWNER/ADMIN cannot bypass, per §8); a MEMBER actor
     * (already confirmed ALL_MEMBERS at step 3) is denied outright if not connected to the target,
     * per §7's worked example ("if B and C were not connected, B could not invite C even with
     * this setting"); an OWNER/ADMIN may always invite an unconnected target -- connection then
     * only decides DIRECT_ADD vs. INVITATION_REQUIRED for them</li>
     * <li>final decision</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public AddMemberEvaluation evaluateAddMember(Long actorUserId, Long groupId, Long targetUserId) {
        requireActiveMember(actorUserId, groupId);

        if (actorUserId.equals(targetUserId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "CANNOT_ADD_SELF");
        }

        GroupRole actorRole = activeRoleOrNull(actorUserId, groupId);
        boolean actorIsElevated = actorRole == GroupRole.OWNER || actorRole == GroupRole.ADMIN;
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
            // regardless of privacy, group policy, or connection status below. No user-level
            // privacy value (ANYONE/CONNECTIONS/NOBODY) changes this.
            return new AddMemberEvaluation(AddMemberDecision.INVITATION_REQUIRED, "REQUIRES_REINVITATION");
        }

        if (isGroupFull(groupId)) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "GROUP_FULL");
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));
        GroupAddPrivacy privacy = resolveGroupAddPrivacy(target);
        if (privacy == GroupAddPrivacy.NOBODY) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "TARGET_PRIVACY_NOBODY");
        }

        boolean connected = isConnected(actorUserId, targetUserId);

        // CONNECTIONS is a target-side veto stronger than group role: only a connected actor may
        // add/invite this target at all, even an owner/admin who could otherwise invite anyone.
        if (privacy == GroupAddPrivacy.CONNECTIONS && !connected) {
            return new AddMemberEvaluation(AddMemberDecision.DENIED, "TARGET_PRIVACY_CONNECTIONS_ONLY");
        }

        if (!connected) {
            if (!actorIsElevated) {
                // A MEMBER's who_can_invite=ALL_MEMBERS permission (already confirmed passing
                // step 3) only ever extends to their own connections -- never downgraded to an
                // invitation for someone they aren't connected to.
                return new AddMemberEvaluation(AddMemberDecision.DENIED, "NO_INVITE_PERMISSION");
            }
            // Not connected -- connection is a precondition for a *silent* add, not for an
            // invitation; an owner/admin can still send one for the target to explicitly accept.
            return new AddMemberEvaluation(AddMemberDecision.INVITATION_REQUIRED, "NOT_CONNECTED");
        }

        return new AddMemberEvaluation(AddMemberDecision.DIRECT_ADD, "CONNECTED");
    }

    // ==================== role management / removal / leave (Stage 3) ====================
    //
    // Permission matrix (docs/CONNECTX_GROUP_ARCHITECTURE.md §6/§10, the pre-approved design this
    // stage implements against -- not invented here):
    //   Promote member -> admin: OWNER only.
    //   Demote admin -> member: OWNER only.
    //   Remove member: OWNER may remove any non-owner (including admins); ADMIN may remove a plain
    //     MEMBER only (not another admin, not the owner); MEMBER may remove nobody.
    //   Leave: MEMBER/ADMIN always allowed; OWNER blocked in V1 (ownership transfer is a separate,
    //     not-yet-implemented stage -- ChatGroup ever having zero or >1 OWNER is not a state this
    //     codebase can reach yet, so "OWNER is the only owner" is unconditionally true today).

    /**
     * OWNER only. Rejects OWNER as a target role (promotion/demotion never produces or removes an
     * owner -- ownership transfer is explicitly out of scope this stage) and rejects the group's
     * own OWNER as a target (their role can never be changed through this operation).
     */
    @Transactional(readOnly = true)
    public void requireCanChangeRole(Long actorUserId, Long groupId, Long targetUserId, GroupRole newRole) {
        GroupRole actorRole = requireRole(actorUserId, groupId);
        if (actorRole != GroupRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Only the group owner can change member roles");
        }
        if (newRole != GroupRole.ADMIN && newRole != GroupRole.MEMBER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "role must be ADMIN or MEMBER");
        }

        GroupRole targetRole = activeRoleOrNull(targetUserId, groupId);
        if (targetRole == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "Target user is not an active member of this group");
        }
        if (targetRole == GroupRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MODIFY_OWNER", "The group owner's role cannot be changed");
        }
    }

    /**
     * OWNER may remove any non-owner member (including admins). ADMIN may remove a plain MEMBER
     * only -- never another admin, never the owner. MEMBER may remove nobody. The owner can never
     * be removed by anyone through this operation, regardless of actor role.
     */
    @Transactional(readOnly = true)
    public void requireCanRemoveMember(Long actorUserId, Long groupId, Long targetUserId) {
        GroupRole actorRole = requireRole(actorUserId, groupId);
        if (actorUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_REMOVE_SELF", "Use the leave endpoint to remove yourself from a group");
        }

        GroupRole targetRole = activeRoleOrNull(targetUserId, groupId);
        if (targetRole == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "Target user is not an active member of this group");
        }
        if (targetRole == GroupRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_REMOVE_OWNER", "The group owner cannot be removed");
        }

        if (actorRole == GroupRole.OWNER) {
            return;
        }
        if (actorRole == GroupRole.ADMIN) {
            if (targetRole == GroupRole.ADMIN) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_CANNOT_REMOVE_ADMIN", "Admins cannot remove other admins");
            }
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "NO_REMOVE_PERMISSION", "You do not have permission to remove members from this group");
    }

    /**
     * MEMBER/ADMIN may always leave. OWNER is blocked -- ownership transfer (the only way to leave
     * without abandoning the group) is a separate, not-yet-implemented stage.
     */
    @Transactional(readOnly = true)
    public void requireCanLeave(Long actorUserId, Long groupId) {
        GroupRole role = requireRole(actorUserId, groupId);
        if (role == GroupRole.OWNER) {
            throw new ApiException(HttpStatus.CONFLICT, "OWNER_CANNOT_LEAVE",
                    "The group owner cannot leave without transferring ownership first");
        }
    }

    private boolean isConnected(Long userId1, Long userId2) {
        Long low = Math.min(userId1, userId2);
        Long high = Math.max(userId1, userId2);
        return userConnectionRepository.existsByUserLowIdAndUserHighId(low, high);
    }

    /**
     * "Who can add me to groups?" (docs/CONNECTX_GROUP_ARCHITECTURE.md §8), persisted since Stage 4
     * on {@code User#groupAddPrivacy}. NULL (every user who existed before this column, and never
     * explicitly set it) means ANYONE -- the single place that interpretation lives, mirroring
     * {@code ProfileVisibilityService}'s identical null-means-EVERYONE convention for
     * {@code profilePhotoVisibility} on the same entity. ANYONE only means the user's own
     * preference does not additionally restrict an otherwise-authorized action; it never bypasses
     * canInvite, blocking, membership state, or the capacity check in {@link #evaluateAddMember}.
     */
    private GroupAddPrivacy resolveGroupAddPrivacy(User target) {
        return target.getGroupAddPrivacy() != null ? target.getGroupAddPrivacy() : GroupAddPrivacy.ANYONE;
    }
}
