package com.connectx.group.service;

import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.GroupInvitationDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import com.connectx.group.entity.WhoCanInvite;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.entity.GroupAddPrivacy;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 4: group settings (the 3 policy ENUMs on chat_groups) and user-level group privacy
 * (users.group_add_privacy), plus their server-side enforcement inside
 * GroupAuthorizationService#evaluateAddMember. Same real-MySQL, service-layer conventions as every
 * other Groups suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupSettingsAndPrivacyTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private UserService userService;
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

    private void addRawMember(Long groupId, User user, GroupRole role) {
        Conversation conversation = conversationRepository.findById(groupId).orElseThrow();
        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        conversationMemberRepository.save(member);
    }

    private void setGroupAddPrivacy(User user, String value) {
        UserProfileUpdateDto dto = new UserProfileUpdateDto();
        dto.setGroupAddPrivacy(value);
        userService.updateUserProfile(user.getId(), dto);
    }

    // ==================== USER PRIVACY ====================

    // 1. a pre-existing user (column left NULL -- predates the migration) behaves as ANYONE. A
    // freshly-created User always gets an explicit ANYONE from @PrePersist (mirroring
    // profilePhotoVisibility's identical convention), so a genuinely-null row is simulated by
    // nulling the column back out on an already-persisted user via a plain UPDATE (onCreate only
    // fires on INSERT, not on this second save) -- exactly the state every pre-migration row is in.
    @Test
    void defaultExistingUserBehavior_isAnyone() {
        User owner = newUser("priv1_owner");
        User target = newUser("priv1_target");
        connect(owner, target);
        target.setGroupAddPrivacy(null);
        userRepository.save(target);
        assertNull(userRepository.findById(target.getId()).orElseThrow().getGroupAddPrivacy(),
                "precondition: simulated pre-migration row with a genuinely NULL column");
        GroupDto group = newGroup(owner, "Priv1 Group");

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("DIRECT_ADDED", result.getOutcome(), "null privacy must behave exactly like ANYONE");
    }

    // 2. user can set ANYONE.
    @Test
    void user_canSetAnyone() {
        User user = newUser("priv2_user");
        setGroupAddPrivacy(user, "ANYONE");
        assertEquals(GroupAddPrivacy.ANYONE, userRepository.findById(user.getId()).orElseThrow().getGroupAddPrivacy());
    }

    // 3. user can set CONNECTIONS.
    @Test
    void user_canSetConnections() {
        User user = newUser("priv3_user");
        setGroupAddPrivacy(user, "CONNECTIONS");
        assertEquals(GroupAddPrivacy.CONNECTIONS, userRepository.findById(user.getId()).orElseThrow().getGroupAddPrivacy());
    }

    // 4. user can set NOBODY.
    @Test
    void user_canSetNobody() {
        User user = newUser("priv4_user");
        setGroupAddPrivacy(user, "NOBODY");
        assertEquals(GroupAddPrivacy.NOBODY, userRepository.findById(user.getId()).orElseThrow().getGroupAddPrivacy());
    }

    // 5. a user cannot modify another user's setting -- UserService#updateUserProfile only ever
    // touches the userId it's given, and UserController's PATCH /users/me only ever sources that
    // id from @AuthenticationPrincipal, never a request body or path parameter.
    @Test
    void user_cannotModifyAnotherUsersSetting() {
        User a = newUser("priv5_a");
        User b = newUser("priv5_b");
        setGroupAddPrivacy(a, "NOBODY");

        setGroupAddPrivacy(b, "CONNECTIONS");

        assertEquals(GroupAddPrivacy.NOBODY, userRepository.findById(a.getId()).orElseThrow().getGroupAddPrivacy(),
                "updating b's setting must never affect a's");
    }

    // 6. an invalid setting value is rejected.
    @Test
    void invalidPrivacySetting_rejected() {
        User user = newUser("priv6_user");
        ApiException ex = assertThrows(ApiException.class, () -> setGroupAddPrivacy(user, "EVERYONE_EXCEPT_MONDAYS"));
        assertEquals("INVALID_GROUP_ADD_PRIVACY", ex.getCode());
    }

    // ==================== GROUP SETTINGS ====================

    // 7. OWNER can update all three settings.
    @Test
    void owner_canUpdateAllSettings() {
        User owner = newUser("set7_owner");
        GroupDto group = newGroup(owner, "Settings7 Group");

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanInvite("ALL_MEMBERS");
        dto.setWhoCanSendMessages("ADMINS_ONLY");
        dto.setWhoCanEditGroupInfo("ALL_MEMBERS");

        GroupDto updated = groupService.updateSettings(owner.getId(), group.getId(), dto);

        assertEquals("ALL_MEMBERS", updated.getWhoCanInvite());
        assertEquals("ADMINS_ONLY", updated.getWhoCanSendMessages());
        assertEquals("ALL_MEMBERS", updated.getWhoCanEditGroupInfo());
    }

    // 8. ADMIN cannot update settings -- per docs/CONNECTX_GROUP_ARCHITECTURE.md §6, all three
    // ENUM settings are owner-only; there is no setting an admin is permitted to change.
    @Test
    void admin_cannotUpdateAnySetting() {
        User owner = newUser("set8_owner");
        User admin = newUser("set8_admin");
        GroupDto group = newGroup(owner, "Settings8 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanInvite("ALL_MEMBERS");
        ApiException ex = assertThrows(ApiException.class, () -> groupService.updateSettings(admin.getId(), group.getId(), dto));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 9. MEMBER cannot update restricted settings.
    @Test
    void member_cannotUpdateSettings() {
        User owner = newUser("set9_owner");
        User member = newUser("set9_member");
        GroupDto group = newGroup(owner, "Settings9 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanEditGroupInfo("ALL_MEMBERS");
        ApiException ex = assertThrows(ApiException.class, () -> groupService.updateSettings(member.getId(), group.getId(), dto));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 10. a forged actor id is rejected.
    @Test
    void forgedActorId_rejectedForSettingsUpdate() {
        User owner = newUser("set10_owner");
        GroupDto group = newGroup(owner, "Settings10 Group");

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanInvite("ALL_MEMBERS");
        long neverIssuedUserId = 987_654_323L;
        ApiException ex = assertThrows(ApiException.class, () -> groupService.updateSettings(neverIssuedUserId, group.getId(), dto));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 11. a forged owner/admin role is rejected -- GroupAuthorizationService always re-derives the
    // actor's role from conversation_members; nothing in UpdateGroupSettingsRequestDto or the
    // request path claims a role.
    @Test
    void forgedRole_rejectedForSettingsUpdate() {
        User owner = newUser("set11_owner");
        User plainMember = newUser("set11_member");
        GroupDto group = newGroup(owner, "Settings11 Group");
        addRawMember(group.getId(), plainMember, GroupRole.MEMBER);

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanSendMessages("ADMINS_ONLY");
        ApiException ex = assertThrows(ApiException.class, () -> groupService.updateSettings(plainMember.getId(), group.getId(), dto));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 12. the owner of one group cannot update a different group's settings (cross-group update).
    @Test
    void crossGroupSettingsUpdate_rejected() {
        User ownerA = newUser("set12_owner_a");
        User ownerB = newUser("set12_owner_b");
        newGroup(ownerA, "Settings12 Group A");
        GroupDto groupB = newGroup(ownerB, "Settings12 Group B");

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanInvite("ALL_MEMBERS");
        ApiException ex = assertThrows(ApiException.class, () -> groupService.updateSettings(ownerA.getId(), groupB.getId(), dto));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertEquals(WhoCanInvite.OWNER_ADMIN_ONLY, chatGroupRepository.findById(groupB.getId()).orElseThrow().getWhoCanInvite(),
                "group B's setting must be untouched");
    }

    // an invalid enum value among otherwise-valid ones commits nothing (atomicity).
    @Test
    void invalidSettingValue_rollsBackWholeRequest() {
        User owner = newUser("set13_owner");
        GroupDto group = newGroup(owner, "Settings13 Group");

        UpdateGroupSettingsRequestDto dto = new UpdateGroupSettingsRequestDto();
        dto.setWhoCanInvite("ALL_MEMBERS");
        dto.setWhoCanSendMessages("NOT_A_REAL_VALUE");
        assertThrows(ApiException.class, () -> groupService.updateSettings(owner.getId(), group.getId(), dto));

        ChatGroup reloaded = chatGroupRepository.findById(group.getId()).orElseThrow();
        assertEquals(WhoCanInvite.OWNER_ADMIN_ONLY, reloaded.getWhoCanInvite(), "the valid field must not have been committed either");
    }

    // ==================== INVITATION DECISIONS ====================

    // 13. ANYONE allows the otherwise-authorized operation to proceed unimpeded.
    @Test
    void anyonePrivacy_allowsEligibleOperation() {
        User owner = newUser("dec13_owner");
        User target = newUser("dec13_target");
        setGroupAddPrivacy(target, "ANYONE");
        connect(owner, target);
        GroupDto group = newGroup(owner, "Decision13 Group");

        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("DIRECT_ADDED", result.getOutcome());
    }

    // 14. CONNECTIONS privacy requires an active connection -- denies outright without one, even
    // from an OWNER who could otherwise invite anyone.
    @Test
    void connectionsPrivacy_requiresActiveConnection() {
        User owner = newUser("dec14_owner");
        User target = newUser("dec14_target");
        setGroupAddPrivacy(target, "CONNECTIONS");
        GroupDto group = newGroup(owner, "Decision14 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("TARGET_PRIVACY_CONNECTIONS_ONLY", ex.getCode());
    }

    // 15. NOBODY denies unauthorized addition/invitation entirely.
    @Test
    void nobodyPrivacy_deniesAddition() {
        User owner = newUser("dec15_owner");
        User target = newUser("dec15_target");
        setGroupAddPrivacy(target, "NOBODY");
        connect(owner, target);
        GroupDto group = newGroup(owner, "Decision15 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("TARGET_PRIVACY_NOBODY", ex.getCode());
    }

    // 16. group invitation policy is respected -- a MEMBER's ALL_MEMBERS invite right only ever
    // extends to their own connections (docs/CONNECTX_GROUP_ARCHITECTURE.md §7's worked example).
    @Test
    void memberWithAllMembers_cannotInviteUnconnectedTarget() {
        User owner = newUser("dec16_owner");
        User member = newUser("dec16_member");
        User target = newUser("dec16_target");
        GroupDto group = newGroup(owner, "Decision16 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        UpdateGroupSettingsRequestDto settings = new UpdateGroupSettingsRequestDto();
        settings.setWhoCanInvite("ALL_MEMBERS");
        groupService.updateSettings(owner.getId(), group.getId(), settings);
        // member and target are deliberately NOT connected.

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                member.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("NO_INVITE_PERMISSION", ex.getCode());
    }

    // 17. a blocked pair remains denied regardless of privacy/policy/connection.
    @Test
    void blockedPair_remainsDenied() {
        User owner = newUser("dec17_owner");
        User target = newUser("dec17_target");
        connect(owner, target);
        setGroupAddPrivacy(target, "ANYONE");
        blockService.blockUser(target.getId(), owner.getId());
        GroupDto group = newGroup(owner, "Decision17 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 18, 19. an inactive (previously left/removed) target still requires a fresh invitation and
    // is never silently restored, regardless of privacy.
    @Test
    void inactiveTarget_stillRequiresInvitation_neverSilentlyRestored() {
        User owner = newUser("dec1819_owner");
        User former = newUser("dec1819_former");
        connect(owner, former);
        setGroupAddPrivacy(former, "ANYONE");
        GroupDto group = newGroup(owner, "Decision1819 Group");
        addRawMember(group.getId(), former, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), former.getId());

        GroupAuthorizationService.AddMemberEvaluation evaluation =
                groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), former.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.INVITATION_REQUIRED, evaluation.getDecision());
        assertNotEquals(GroupAuthorizationService.AddMemberDecision.DIRECT_ADD, evaluation.getDecision());
    }

    // 20. capacity is still enforced regardless of privacy.
    @Test
    void capacity_stillEnforced() {
        User owner = newUser("dec20_owner");
        User target = newUser("dec20_target");
        connect(owner, target);
        setGroupAddPrivacy(target, "ANYONE");
        GroupDto group = newGroup(owner, "Decision20 Group");
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;
        for (int i = 0; i < cap - 1; i++) {
            addRawMember(group.getId(), newUser("dec20_filler_" + i), GroupRole.MEMBER);
        }
        assertTrue(groupAuthorizationService.isGroupFull(group.getId()));

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());
    }

    // 21. connection does not bypass NOBODY.
    @Test
    void connection_doesNotBypassNobody() {
        User owner = newUser("dec21_owner");
        User target = newUser("dec21_target");
        connect(owner, target);
        setGroupAddPrivacy(target, "NOBODY");
        GroupDto group = newGroup(owner, "Decision21 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("TARGET_PRIVACY_NOBODY", ex.getCode());
    }

    // 22. group policy (even an owner's normally-unconditional invite-anyone right) does not
    // bypass NOBODY.
    @Test
    void groupPolicy_doesNotBypassNobody() {
        User owner = newUser("dec22_owner");
        User target = newUser("dec22_target");
        setGroupAddPrivacy(target, "NOBODY");
        // Not connected either -- owner would normally still be allowed to send an invitation
        // (INVITATION_REQUIRED) to an unconnected user; NOBODY must deny this outright instead.
        GroupDto group = newGroup(owner, "Decision22 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("TARGET_PRIVACY_NOBODY", ex.getCode());
    }

    // ==================== SETTINGS CHANGE + INVITATION ====================

    // 23, 26. a group policy change after an invitation was already sent does not retroactively
    // cancel or invalidate it -- "settings control future authorization behavior," matching the
    // instruction not to auto-delete existing invitations on a settings change. The invitation
    // remains exactly as acceptable as when it was created.
    @Test
    void policyChangeAfterInvitationSent_doesNotInvalidateIt() {
        User owner = newUser("chg2326_owner");
        User member = newUser("chg2326_member");
        User target = newUser("chg2326_target");
        connect(member, target);
        GroupDto group = newGroup(owner, "Change2326 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        UpdateGroupSettingsRequestDto allowAll = new UpdateGroupSettingsRequestDto();
        allowAll.setWhoCanInvite("ALL_MEMBERS");
        groupService.updateSettings(owner.getId(), group.getId(), allowAll);

        // Not connected to owner, so this stays an actual PENDING invitation from the owner.
        Long invitationId = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())).getInvitation().getId();

        UpdateGroupSettingsRequestDto restrictAgain = new UpdateGroupSettingsRequestDto();
        restrictAgain.setWhoCanInvite("OWNER_ADMIN_ONLY");
        groupService.updateSettings(owner.getId(), group.getId(), restrictAgain);

        GroupInvitation stillPending = groupInvitationRepository.findById(invitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.PENDING, stillPending.getStatus(), "a policy change must not auto-cancel an existing invitation");

        GroupInvitationDto accepted = groupInvitationService.acceptInvitation(target.getId(), invitationId);
        assertEquals("ACCEPTED", accepted.getStatus());
    }

    // 24, 25. changing a user's privacy setting has no membership side effects at all -- it does
    // not create a membership anywhere, and does not touch any existing membership row.
    @Test
    void changingPrivacy_hasNoMembershipSideEffects() {
        User owner = newUser("chg2425_owner");
        User user = newUser("chg2425_user");
        connect(owner, user);
        GroupDto group = newGroup(owner, "Change2425 Group");
        addRawMember(group.getId(), user, GroupRole.MEMBER);
        Instant beforeDeletedAt = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), user.getId())
                .orElseThrow().getDeletedAt();
        long conversationMemberRowsBefore = conversationMemberRepository.count();

        setGroupAddPrivacy(user, "NOBODY");
        setGroupAddPrivacy(user, "CONNECTIONS");
        setGroupAddPrivacy(user, "ANYONE");

        assertEquals(conversationMemberRowsBefore, conversationMemberRepository.count(), "no membership row may be created as a side effect");
        ConversationMember afterChange = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), user.getId()).orElseThrow();
        assertEquals(beforeDeletedAt, afterChange.getDeletedAt());
        assertEquals(GroupRole.MEMBER, afterChange.getRole());
    }

    // A NOBODY setting must never block the target's OWN voluntary acceptance of an
    // already-existing invitation -- it only prevents others from adding/inviting them, per the
    // explicit "do not prevent the target voluntarily accepting a valid invitation" instruction.
    @Test
    void nobodyPrivacySetAfterInvitationSent_doesNotBlockOwnVoluntaryAcceptance() {
        User owner = newUser("nobody_accept_owner");
        User target = newUser("nobody_accept_target");
        GroupDto group = newGroup(owner, "Nobody Accept Group");
        Long invitationId = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())).getInvitation().getId();

        setGroupAddPrivacy(target, "NOBODY");

        GroupInvitationDto accepted = groupInvitationService.acceptInvitation(target.getId(), invitationId);
        assertEquals("ACCEPTED", accepted.getStatus());
        assertEquals(GroupAuthorizationService.MembershipState.ACTIVE_MEMBER,
                groupAuthorizationService.resolveMembershipState(group.getId(), target.getId()));
    }
}
