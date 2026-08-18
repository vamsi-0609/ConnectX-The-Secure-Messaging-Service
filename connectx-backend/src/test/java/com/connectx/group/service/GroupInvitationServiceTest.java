package com.connectx.group.service;

import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.GroupInvitationDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import com.connectx.group.entity.WhoCanInvite;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 2: invitation creation, accept/reject/cancel, and listing. Follows the same
 * real-MySQL, service-layer conventions as GroupAuthorizationServiceTest -- GroupInvitationService
 * is exercised directly, no MockMvc, since forging an actor/role/target has nothing to do with the
 * HTTP layer (CreateGroupInvitationRequestDto carries only targetUserId).
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupInvitationServiceTest {

    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
    @Autowired
    private GroupInvitationRepository groupInvitationRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private void connect(User a, User b) {
        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), connectionService.getPendingIncomingRequests(b.getId()).get(0).getId());
    }

    private GroupDto newGroup(User owner, String name) {
        return groupService.createGroup(owner.getId(), new CreateGroupRequestDto(name, null));
    }

    private void addRawMember(Long conversationId, User user, GroupRole role) {
        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        conversationMemberRepository.save(member);
    }

    private void setWhoCanInvite(Long groupId, WhoCanInvite value) {
        ChatGroup chatGroup = chatGroupRepository.findById(groupId).orElseThrow();
        chatGroup.setWhoCanInvite(value);
        chatGroupRepository.save(chatGroup);
    }

    // ==================== CREATION ====================

    // 1. OWNER can invite when allowed (default OWNER_ADMIN_ONLY; not connected -> INVITATION_REQUIRED).
    @Test
    void owner_canInvite_whenAllowed() {
        User owner = newUser("inv_owner1");
        User target = newUser("inv_owner1_target");
        GroupDto group = newGroup(owner, "Owner Invite Group");

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        assertEquals("INVITATION_SENT", result.getOutcome());
        assertEquals("PENDING", result.getInvitation().getStatus());
        assertEquals(target.getId(), result.getInvitation().getInvitee().getId());
        assertEquals(owner.getId(), result.getInvitation().getInvitedBy().getId());
    }

    // 2. ADMIN can invite when allowed.
    @Test
    void admin_canInvite_whenAllowed() {
        User owner = newUser("inv_owner2");
        User admin = newUser("inv_admin2");
        User target = newUser("inv_admin2_target");
        GroupDto group = newGroup(owner, "Admin Invite Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                admin.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        assertEquals("INVITATION_SENT", result.getOutcome());
    }

    // 3. MEMBER cannot invite when OWNER_ADMIN_ONLY (the default).
    @Test
    void member_cannotInvite_whenOwnerAdminOnly() {
        User owner = newUser("inv_owner3");
        User member = newUser("inv_member3");
        User target = newUser("inv_member3_target");
        GroupDto group = newGroup(owner, "Member Restricted Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                member.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("NO_INVITE_PERMISSION", ex.getCode());
    }

    // 4. MEMBER can invite when ALL_MEMBERS -- but per
    // docs/CONNECTX_GROUP_ARCHITECTURE.md §7's worked example, only a connected target: "B may
    // invite C because B and C are connected." (Stage 4 corrected this: previously a MEMBER could
    // invite anyone under ALL_MEMBERS, connection only affecting DIRECT_ADD vs
    // INVITATION_REQUIRED -- see GroupSettingsAndPrivacyTest#memberWithAllMembers_cannotInviteUnconnectedTarget
    // for the case of an unconnected target, which is now correctly DENIED instead.)
    @Test
    void member_canInvite_whenAllMembers() {
        User owner = newUser("inv_owner4");
        User member = newUser("inv_member4");
        User target = newUser("inv_member4_target");
        connect(member, target);
        GroupDto group = newGroup(owner, "All Members Invite Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        setWhoCanInvite(group.getId(), WhoCanInvite.ALL_MEMBERS);

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                member.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("DIRECT_ADDED", result.getOutcome());
    }

    // 5. a non-member cannot invite.
    @Test
    void nonMember_cannotInvite() {
        User owner = newUser("inv_owner5");
        User outsider = newUser("inv_outsider5");
        User target = newUser("inv_target5");
        GroupDto group = newGroup(owner, "NonMember Invite Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                outsider.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 6. target user must exist.
    @Test
    void targetUser_mustExist() {
        User owner = newUser("inv_owner6");
        GroupDto group = newGroup(owner, "Nonexistent Target Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(999_999_999L)));
        assertEquals("USER_NOT_FOUND", ex.getCode());
    }

    // 7. an already-active member cannot be invited.
    @Test
    void alreadyActiveMember_cannotBeInvited() {
        User owner = newUser("inv_owner7");
        User existingMember = newUser("inv_member7");
        GroupDto group = newGroup(owner, "Already Member Group");
        addRawMember(group.getId(), existingMember, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(existingMember.getId())));
        assertEquals("ALREADY_GROUP_MEMBER", ex.getCode());
    }

    // 8. a blocked target is handled correctly -- DENIED, regardless of connection/permission.
    @Test
    void blockedTarget_isDenied() {
        User owner = newUser("inv_owner8");
        User target = newUser("inv_target8");
        connect(owner, target);
        blockService.blockUser(target.getId(), owner.getId());
        GroupDto group = newGroup(owner, "Blocked Target Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 9. a connected target (NEVER_MEMBER, default ANYONE privacy) is added directly.
    @Test
    void connectedTarget_isDirectlyAdded() {
        User owner = newUser("inv_owner9");
        User target = newUser("inv_target9");
        connect(owner, target);
        GroupDto group = newGroup(owner, "Connected Direct Add Group");

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        assertEquals("DIRECT_ADDED", result.getOutcome());
        assertNull(result.getInvitation());
        assertEquals(GroupAuthorizationService.MembershipState.ACTIVE_MEMBER,
                groupAuthorizationService.resolveMembershipState(group.getId(), target.getId()));
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).orElseThrow();
        assertEquals(GroupRole.MEMBER, member.getRole());
    }

    // 10. a non-connected target requires an explicit invitation instead of a silent add.
    @Test
    void nonConnectedTarget_requiresInvitation() {
        User owner = newUser("inv_owner10");
        User target = newUser("inv_target10");
        GroupDto group = newGroup(owner, "Non Connected Group");

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        assertEquals("INVITATION_SENT", result.getOutcome());
        assertEquals("PENDING", result.getInvitation().getStatus());
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).isEmpty());
    }

    // 11. a previously inactive (left/removed) target always requires a fresh invitation, even
    // when connected and privacy is (the current default) ANYONE.
    @Test
    void previouslyInactiveTarget_alwaysRequiresInvitation() {
        User owner = newUser("inv_owner11");
        User former = newUser("inv_former11");
        connect(owner, former);
        GroupDto group = newGroup(owner, "Inactive Target Group");
        addRawMember(group.getId(), former, GroupRole.MEMBER);
        ConversationMember membership = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), former.getId()).orElseThrow();
        membership.setDeletedAt(Instant.now());
        conversationMemberRepository.save(membership);

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(former.getId()));

        assertEquals("INVITATION_SENT", result.getOutcome());
        assertNotEquals("DIRECT_ADDED", result.getOutcome());
    }

    // 12. the inviter cannot be forged -- CreateGroupInvitationRequestDto carries only
    // targetUserId; invitedBy is always the actorUserId parameter, sourced only from the
    // authenticated principal at the controller boundary.
    @Test
    void inviter_cannotBeForged() {
        User ownerA = newUser("inv_forge_a");
        User ownerB = newUser("inv_forge_b");
        User targetA = newUser("inv_forge_target_a");
        User targetB = newUser("inv_forge_target_b");
        GroupDto groupA = newGroup(ownerA, "Forge Group A");
        GroupDto groupB = newGroup(ownerB, "Forge Group B");

        CreateGroupInvitationResponseDto resultA = groupInvitationService.createInvitation(
                ownerA.getId(), groupA.getId(), new CreateGroupInvitationRequestDto(targetA.getId()));
        CreateGroupInvitationResponseDto resultB = groupInvitationService.createInvitation(
                ownerB.getId(), groupB.getId(), new CreateGroupInvitationRequestDto(targetB.getId()));

        assertEquals(ownerA.getId(), resultA.getInvitation().getInvitedBy().getId());
        assertEquals(ownerB.getId(), resultB.getInvitation().getInvitedBy().getId());
    }

    // 13. no role can be forged into permission -- the DTO has no role field, and a plain MEMBER
    // in an OWNER_ADMIN_ONLY group is denied purely based on their DB-recorded role (see also
    // GroupAuthorizationServiceTest#forgedRole_cannotGrantAuthority for the lower-level guarantee
    // this depends on).
    @Test
    void role_cannotBeForgedIntoInvitePermission() {
        User owner = newUser("inv_owner13");
        User member = newUser("inv_member13");
        User target = newUser("inv_target13");
        GroupDto group = newGroup(owner, "Forge Role Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                member.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("NO_INVITE_PERMISSION", ex.getCode());
    }

    // ==================== ACCEPTANCE ====================

    // 14, 23, 24, 25. the target can accept; becomes an active MEMBER; can access the group afterward.
    @Test
    void target_canAccept_andBecomesActiveMember() {
        User owner = newUser("acc_owner14");
        User target = newUser("acc_target14");
        GroupDto group = newGroup(owner, "Accept Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();

        GroupInvitationDto accepted = groupInvitationService.acceptInvitation(target.getId(), invitationId);

        assertEquals("ACCEPTED", accepted.getStatus());
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).orElseThrow();
        assertEquals(GroupRole.MEMBER, member.getRole());
        assertNull(member.getDeletedAt());
        assertTrue(groupAuthorizationService.canViewGroup(target.getId(), group.getId()));
        assertDoesNotThrow(() -> groupService.getGroupDetails(target.getId(), group.getId()));
    }

    // 15. a non-target cannot accept.
    @Test
    void nonTarget_cannotAccept() {
        User owner = newUser("acc_owner15");
        User target = newUser("acc_target15");
        User outsider = newUser("acc_outsider15");
        GroupDto group = newGroup(owner, "Accept Reject Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> groupInvitationService.acceptInvitation(outsider.getId(), created.getInvitation().getId()));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // 16. the inviter cannot accept on behalf of the target.
    @Test
    void inviter_cannotAcceptForTarget() {
        User owner = newUser("acc_owner16");
        User target = newUser("acc_target16");
        GroupDto group = newGroup(owner, "Inviter Accept Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> groupInvitationService.acceptInvitation(owner.getId(), created.getInvitation().getId()));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // 18. a rejected invitation cannot be accepted.
    @Test
    void rejectedInvitation_cannotBeAccepted() {
        User owner = newUser("acc_owner18");
        User target = newUser("acc_target18");
        GroupDto group = newGroup(owner, "Rejected Then Accept Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();
        groupInvitationService.rejectInvitation(target.getId(), invitationId);

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("INVITATION_NOT_PENDING", ex.getCode());
    }

    // 19. a cancelled invitation cannot be accepted.
    @Test
    void cancelledInvitation_cannotBeAccepted() {
        User owner = newUser("acc_owner19");
        User target = newUser("acc_target19");
        GroupDto group = newGroup(owner, "Cancelled Then Accept Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();
        groupInvitationService.cancelInvitation(owner.getId(), invitationId);

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("INVITATION_NOT_PENDING", ex.getCode());
    }

    // 20. a duplicate (sequential) acceptance is safe: no crash, no second membership row.
    @Test
    void duplicateAcceptance_isSafe() {
        User owner = newUser("acc_owner20");
        User target = newUser("acc_target20");
        GroupDto group = newGroup(owner, "Duplicate Accept Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();

        groupInvitationService.acceptInvitation(target.getId(), invitationId);
        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("INVITATION_NOT_PENDING", ex.getCode());

        long rows = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(target.getId())).count();
        assertEquals(1, rows);
    }

    // 21. capacity is checked again at acceptance time, not assumed from invitation-creation time.
    @Test
    void capacity_isReCheckedAtAcceptance() {
        User owner = newUser("acc_owner21");
        User target = newUser("acc_target21");
        GroupDto group = newGroup(owner, "Capacity Recheck Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();

        // Fill the group to the cap via other members between invitation creation and acceptance.
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;
        for (int i = 0; i < cap - 1; i++) {
            addRawMember(group.getId(), newUser("acc_filler21_" + i), GroupRole.MEMBER);
        }
        assertTrue(groupAuthorizationService.isGroupFull(group.getId()));

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());

        GroupInvitation reloaded = groupInvitationRepository.findById(invitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.PENDING, reloaded.getStatus(), "a failed accept must not mark the invitation ACCEPTED");
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).isEmpty());
    }

    // 22. blocking is re-checked at acceptance time, not assumed from invitation-creation time.
    @Test
    void blocking_isReCheckedAtAcceptance() {
        User owner = newUser("acc_owner22");
        User target = newUser("acc_target22");
        GroupDto group = newGroup(owner, "Block Recheck Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();

        blockService.blockUser(target.getId(), owner.getId());

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("BLOCKED", ex.getCode());

        GroupInvitation reloaded = groupInvitationRepository.findById(invitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.PENDING, reloaded.getStatus());
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).isEmpty());
    }

    // ==================== REJECTION ====================

    // 26. the target can reject.
    @Test
    void target_canReject() {
        User owner = newUser("rej_owner26");
        User target = newUser("rej_target26");
        GroupDto group = newGroup(owner, "Reject Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        GroupInvitationDto rejected = groupInvitationService.rejectInvitation(target.getId(), created.getInvitation().getId());
        assertEquals("REJECTED", rejected.getStatus());
    }

    // 27. a non-target cannot reject.
    @Test
    void nonTarget_cannotReject() {
        User owner = newUser("rej_owner27");
        User target = newUser("rej_target27");
        User outsider = newUser("rej_outsider27");
        GroupDto group = newGroup(owner, "Reject Outsider Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> groupInvitationService.rejectInvitation(outsider.getId(), created.getInvitation().getId()));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // 28. a rejected invitation creates no membership.
    @Test
    void rejectedInvitation_createsNoMembership() {
        User owner = newUser("rej_owner28");
        User target = newUser("rej_target28");
        GroupDto group = newGroup(owner, "Reject No Membership Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        groupInvitationService.rejectInvitation(target.getId(), created.getInvitation().getId());

        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).isEmpty());
    }

    // ==================== CANCELLATION ====================

    // 29. the inviter can cancel.
    @Test
    void inviter_canCancel() {
        User owner = newUser("can_owner29");
        User target = newUser("can_target29");
        GroupDto group = newGroup(owner, "Cancel Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        GroupInvitationDto cancelled = groupInvitationService.cancelInvitation(owner.getId(), created.getInvitation().getId());
        assertEquals("CANCELLED", cancelled.getStatus());
    }

    // 30. a non-inviter cannot cancel (including the target themselves).
    @Test
    void nonInviter_cannotCancel() {
        User owner = newUser("can_owner30");
        User target = newUser("can_target30");
        User outsider = newUser("can_outsider30");
        GroupDto group = newGroup(owner, "Cancel Outsider Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        ApiException exOutsider = assertThrows(ApiException.class,
                () -> groupInvitationService.cancelInvitation(outsider.getId(), created.getInvitation().getId()));
        assertEquals("FORBIDDEN", exOutsider.getCode());

        ApiException exTarget = assertThrows(ApiException.class,
                () -> groupInvitationService.cancelInvitation(target.getId(), created.getInvitation().getId()));
        assertEquals("FORBIDDEN", exTarget.getCode());
    }

    // 31. a cancelled invitation creates no membership.
    @Test
    void cancelledInvitation_createsNoMembership() {
        User owner = newUser("can_owner31");
        User target = newUser("can_target31");
        GroupDto group = newGroup(owner, "Cancel No Membership Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        groupInvitationService.cancelInvitation(owner.getId(), created.getInvitation().getId());

        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).isEmpty());
    }

    // ==================== LISTING (scoped to caller) ====================

    @Test
    void pendingInvitationLists_areScopedToCaller() {
        User owner = newUser("list_owner");
        User target = newUser("list_target");
        User unrelated = newUser("list_unrelated");
        GroupDto group = newGroup(owner, "Listing Group");
        groupInvitationService.createInvitation(owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));

        List<GroupInvitationDto> received = groupInvitationService.getReceivedPendingInvitations(target.getId());
        assertEquals(1, received.size());
        assertEquals(target.getId(), received.get(0).getInvitee().getId());

        List<GroupInvitationDto> sent = groupInvitationService.getSentPendingInvitations(owner.getId());
        assertEquals(1, sent.size());
        assertEquals(owner.getId(), sent.get(0).getInvitedBy().getId());

        assertTrue(groupInvitationService.getReceivedPendingInvitations(unrelated.getId()).isEmpty());
        assertTrue(groupInvitationService.getSentPendingInvitations(unrelated.getId()).isEmpty());
    }

    // ==================== REGRESSION ====================

    // 36. DIRECT conversation creation still works.
    @Test
    void directConversationCreation_stillWorks() {
        User a = newUser("regress_direct_a");
        User b = newUser("regress_direct_b");
        connect(a, b);

        ConversationDto direct = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals("DIRECT", direct.getType());
    }

    // 38. legacy (pre-connection-system) DIRECT conversations still work.
    @Test
    void legacyDirectConversations_stillWork() {
        User a = newUser("regress_legacy_a");
        User b = newUser("regress_legacy_b");
        Conversation legacyDirect = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, a));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, b));

        ConversationDto reopened = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals(legacyDirect.getId(), reopened.getId());
    }
}
