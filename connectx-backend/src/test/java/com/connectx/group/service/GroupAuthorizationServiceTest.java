package com.connectx.group.service;

import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 1.5: the central server-side authorization model every future group operation
 * (invitations, member management, group messaging, settings) must go through.
 * GroupAuthorizationService is exercised directly, same real-MySQL/service-layer conventions as
 * every other authorization suite in this codebase (DirectConversationAuthorizationTest,
 * BlockEnforcementIntegrationTest).
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupAuthorizationServiceTest {

    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private GroupService groupService;
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

    // 1, 2, 3. OWNER/ADMIN/MEMBER roles are recognized correctly, re-derived from the DB.
    @Test
    void roles_recognizedCorrectlyForOwnerAdminAndMember() {
        User owner = newUser("role_owner");
        GroupDto group = newGroup(owner, "Role Group");
        User admin = newUser("role_admin");
        User member = newUser("role_member");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        assertEquals(GroupRole.OWNER, groupAuthorizationService.requireRole(owner.getId(), group.getId()));
        assertEquals(GroupRole.ADMIN, groupAuthorizationService.requireRole(admin.getId(), group.getId()));
        assertEquals(GroupRole.MEMBER, groupAuthorizationService.requireRole(member.getId(), group.getId()));

        assertDoesNotThrow(() -> groupAuthorizationService.requireOwner(owner.getId(), group.getId()));
        assertThrows(ApiException.class, () -> groupAuthorizationService.requireOwner(admin.getId(), group.getId()));

        assertDoesNotThrow(() -> groupAuthorizationService.requireAdminOrOwner(owner.getId(), group.getId()));
        assertDoesNotThrow(() -> groupAuthorizationService.requireAdminOrOwner(admin.getId(), group.getId()));
        assertThrows(ApiException.class, () -> groupAuthorizationService.requireAdminOrOwner(member.getId(), group.getId()));
    }

    // 4. a non-member is rejected by the central active-membership gate.
    @Test
    void nonMember_rejectedByRequireActiveMember() {
        User owner = newUser("nm_owner");
        User outsider = newUser("nm_outsider");
        GroupDto group = newGroup(owner, "NonMember Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertFalse(groupAuthorizationService.canViewGroup(outsider.getId(), group.getId()));
    }

    // 5. a soft-deleted (former) member is rejected exactly like a non-member.
    @Test
    void deletedMember_rejectedByRequireActiveMember() {
        User owner = newUser("del_owner");
        User former = newUser("del_former");
        GroupDto group = newGroup(owner, "Deleted Member Group");
        addRawMember(group.getId(), former, GroupRole.MEMBER);
        ConversationMember membership = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), former.getId()).orElseThrow();
        membership.setDeletedAt(Instant.now());
        conversationMemberRepository.save(membership);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(former.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 6, 7. the group must exist, and its conversation must actually be type GROUP -- a DIRECT
    // conversation id is rejected the same way a non-existent id is.
    @Test
    void groupMustExistAndBeGroupType() {
        User a = newUser("type_a");
        User b = newUser("type_b");

        ApiException notFound = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(a.getId(), 999_999_999L));
        assertEquals("GROUP_NOT_FOUND", notFound.getCode());

        Conversation direct = conversationRepository.save(new Conversation(com.connectx.conversation.entity.ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        ApiException wrongType = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", wrongType.getCode());
    }

    // 8, 9. the central 50-active-member cap is enforced, and soft-deleted members are excluded --
    // exercised through GroupAuthorizationService.isGroupFull directly (see also
    // GroupServiceTest#memberLimit_enforcedButExcludesSoftDeletedMembers for the addMember-level
    // enforcement of the same cap).
    @Test
    void activeGroupMemberCap_enforcedExcludingSoftDeleted() {
        assertEquals(50, GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS);

        User owner = newUser("cap_owner");
        GroupDto group = newGroup(owner, "Cap Group");
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;

        User lastToDelete = null;
        for (int i = 0; i < cap - 1; i++) {
            User filler = newUser("cap_filler_" + i);
            addRawMember(group.getId(), filler, GroupRole.MEMBER);
            if (i == 0) {
                lastToDelete = filler;
            }
        }
        assertEquals(cap, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));
        assertTrue(groupAuthorizationService.isGroupFull(group.getId()));

        ConversationMember toRemove = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), lastToDelete.getId()).orElseThrow();
        toRemove.setDeletedAt(Instant.now());
        conversationMemberRepository.save(toRemove);

        assertEquals(cap - 1, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));
        assertFalse(groupAuthorizationService.isGroupFull(group.getId()));
    }

    // 10, 11. a previously LEFT/REMOVED member (both collapsed into MembershipState.INACTIVE --
    // see GroupAuthorizationService's class-level javadoc on why this codebase cannot currently
    // distinguish the two) must never resolve to DIRECT_ADD -- always INVITATION_REQUIRED, even
    // when connected and the target's (currently-default) privacy is ANYONE.
    @Test
    void inactiveMember_neverSilentlyTreatedAsActive_regardlessOfWhyTheyLeftOrWereRemoved() {
        User owner = newUser("inactive_owner");
        User former = newUser("inactive_former");
        connect(owner, former);
        GroupDto group = newGroup(owner, "Inactive Group");
        addRawMember(group.getId(), former, GroupRole.MEMBER);

        // Case A: simulates a voluntary LEFT.
        ConversationMember left = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), former.getId()).orElseThrow();
        left.setDeletedAt(Instant.now());
        conversationMemberRepository.save(left);

        assertEquals(GroupAuthorizationService.MembershipState.INACTIVE,
                groupAuthorizationService.resolveMembershipState(group.getId(), former.getId()));
        GroupAuthorizationService.AddMemberEvaluation evalAfterLeave = groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), former.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.INVITATION_REQUIRED, evalAfterLeave.getDecision());
        assertNotEquals(GroupAuthorizationService.AddMemberDecision.DIRECT_ADD, evalAfterLeave.getDecision());

        // Case B: this codebase has no way to record "removed by an admin" differently from "left"
        // on conversation_members today (both are just deletedAt != null) -- so the exact same row
        // state stands in for a REMOVED member too, and must produce the identical, safe outcome.
        GroupAuthorizationService.AddMemberEvaluation evalAfterRemoval = groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), former.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.INVITATION_REQUIRED, evalAfterRemoval.getDecision());
        assertEquals("REQUIRES_REINVITATION", evalAfterRemoval.getReasonCode());

        // And GroupService#addMember (the actual write path) must likewise refuse to silently
        // reactivate the row rather than restoring it, unlike DIRECT's restore-on-reopen pattern.
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.addMember(conversation, former, GroupRole.MEMBER, owner.getId()));
        assertEquals("GROUP_REINVITATION_REQUIRED", ex.getCode());
    }

    // 12. being connected to a group member does not itself grant membership or group access.
    @Test
    void connection_doesNotImplyMembership() {
        User owner = newUser("conn_owner");
        User connectedOutsider = newUser("conn_outsider");
        connect(owner, connectedOutsider);
        GroupDto group = newGroup(owner, "Connection Group");

        assertFalse(groupAuthorizationService.canViewGroup(connectedOutsider.getId(), group.getId()));
        ApiException ex = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(connectedOutsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), connectedOutsider.getId()).isEmpty());
    }

    // 13. a blocked relationship denies the add-member evaluation outright, even when every other
    // condition (connected, room in the group, invite permission) would otherwise allow it.
    @Test
    void blockedRelationship_deniesAddMemberEvaluation() {
        User owner = newUser("blocked_owner");
        User target = newUser("blocked_target");
        connect(owner, target);
        blockService.blockUser(target.getId(), owner.getId());
        GroupDto group = newGroup(owner, "Blocked Group");

        GroupAuthorizationService.AddMemberEvaluation evaluation = groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), target.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.DENIED, evaluation.getDecision());
        assertEquals("BLOCKED", evaluation.getReasonCode());
    }

    // 14. a "forged" actor id cannot become an authority -- every authorization method here only
    // ever trusts what the DB says about that id's actual membership/role, never anything the
    // caller additionally claims. Demonstrated by an arbitrary, never-issued user id and by a real
    // connected-but-unrelated user, neither of which can act as if they were the owner.
    @Test
    void forgedActorId_cannotBecomeAnAuthority() {
        User owner = newUser("forge_actor_owner");
        GroupDto group = newGroup(owner, "Forge Actor Group");

        // An id that was never issued to any real, authenticated session.
        long neverIssuedUserId = 987_654_321L;
        ApiException ex = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(neverIssuedUserId, group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());

        // A real user, connected to the owner, still cannot act as a member/owner just by being
        // named as the actor -- GroupAuthorizationService independently re-derives membership from
        // conversation_members every time, never from what's merely passed in.
        User connectedButUnrelated = newUser("forge_actor_bystander");
        connect(owner, connectedButUnrelated);
        assertThrows(ApiException.class, () -> groupAuthorizationService.requireOwner(connectedButUnrelated.getId(), group.getId()));
    }

    // 15. a "forged" role cannot become authority -- no method here accepts a role as a parameter;
    // requireOwner/requireAdminOrOwner/canManageMembers all re-derive the role from the DB row for
    // (actorUserId, groupId), so a plain MEMBER can never pass an OWNER-only gate no matter what.
    @Test
    void forgedRole_cannotGrantAuthority() {
        User owner = newUser("forge_role_owner");
        User plainMember = newUser("forge_role_member");
        GroupDto group = newGroup(owner, "Forge Role Group");
        addRawMember(group.getId(), plainMember, GroupRole.MEMBER);

        assertEquals(GroupRole.MEMBER, groupAuthorizationService.requireRole(plainMember.getId(), group.getId()));
        assertThrows(ApiException.class, () -> groupAuthorizationService.requireOwner(plainMember.getId(), group.getId()));
        assertThrows(ApiException.class, () -> groupAuthorizationService.requireAdminOrOwner(plainMember.getId(), group.getId()));
        assertFalse(groupAuthorizationService.canManageMembers(plainMember.getId(), group.getId()));
    }

    // 16. DIRECT conversation authorization is unaffected by the group authorization layer.
    @Test
    void directConversationAuthorization_remainsUnchanged() {
        User a = newUser("direct_unaffected_a");
        User b = newUser("direct_unaffected_b");
        connect(a, b);

        ConversationDto direct = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals("DIRECT", direct.getType());

        // GroupAuthorizationService correctly refuses to treat a DIRECT conversation id as a group.
        ApiException ex = assertThrows(ApiException.class,
                () -> groupAuthorizationService.requireActiveMember(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());
    }
}
