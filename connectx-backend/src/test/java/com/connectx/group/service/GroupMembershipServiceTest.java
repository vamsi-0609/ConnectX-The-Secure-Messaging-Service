package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.config.WebSocketAuthChannelInterceptor;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.ConversationMemberDto;
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
import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 3: role management, member removal, voluntary leave, and re-entry consent. Same
 * real-MySQL, service-layer conventions as every other Groups suite -- GroupService/
 * GroupAuthorizationService are exercised directly, no MockMvc, since forging an actor/target/role
 * has nothing to do with the HTTP layer.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupMembershipServiceTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private GroupInvitationRepository groupInvitationRepository;
    @Autowired
    private UserConnectionRepository userConnectionRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WebSocketAuthChannelInterceptor webSocketAuthChannelInterceptor;

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

    private GroupRole roleOf(Long groupId, Long userId) {
        return conversationMemberRepository.findByConversationIdAndUserId(groupId, userId).orElseThrow().getRole();
    }

    // ==================== ROLE TESTS ====================

    // 1. OWNER can promote MEMBER -> ADMIN.
    @Test
    void owner_canPromoteMemberToAdmin() {
        User owner = newUser("role1_owner");
        User member = newUser("role1_member");
        GroupDto group = newGroup(owner, "Role1 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ConversationMemberDto result = groupService.changeRole(owner.getId(), group.getId(), member.getId(), GroupRole.ADMIN);

        assertEquals("ADMIN", result.getRole());
        assertEquals(GroupRole.ADMIN, roleOf(group.getId(), member.getId()));
    }

    // 2. OWNER can demote ADMIN -> MEMBER.
    @Test
    void owner_canDemoteAdminToMember() {
        User owner = newUser("role2_owner");
        User admin = newUser("role2_admin");
        GroupDto group = newGroup(owner, "Role2 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ConversationMemberDto result = groupService.changeRole(owner.getId(), group.getId(), admin.getId(), GroupRole.MEMBER);

        assertEquals("MEMBER", result.getRole());
        assertEquals(GroupRole.MEMBER, roleOf(group.getId(), admin.getId()));
    }

    // 3. OWNER cannot be demoted (targeting the owner with any role is rejected).
    @Test
    void owner_cannotBeDemoted() {
        User owner = newUser("role3_owner");
        User admin = newUser("role3_admin");
        GroupDto group = newGroup(owner, "Role3 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(admin.getId(), group.getId(), owner.getId(), GroupRole.MEMBER));
        // admin actor is blocked at the OWNER_ONLY gate before target-role is even considered --
        // still proves the outcome: an admin can never demote the owner.
        assertEquals("OWNER_ONLY", ex.getCode());
        assertEquals(GroupRole.OWNER, roleOf(group.getId(), owner.getId()));
    }

    // 4. OWNER cannot be removed.
    @Test
    void owner_cannotBeRemoved() {
        User owner = newUser("role4_owner");
        User admin = newUser("role4_admin");
        GroupDto group = newGroup(owner, "Role4 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.removeMember(admin.getId(), group.getId(), owner.getId()));
        assertEquals("CANNOT_REMOVE_OWNER", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // 5. MEMBER cannot promote.
    @Test
    void member_cannotPromote() {
        User owner = newUser("role5_owner");
        User member = newUser("role5_member");
        User other = newUser("role5_other");
        GroupDto group = newGroup(owner, "Role5 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        addRawMember(group.getId(), other, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(member.getId(), group.getId(), other.getId(), GroupRole.ADMIN));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 6. MEMBER cannot demote.
    @Test
    void member_cannotDemote() {
        User owner = newUser("role6_owner");
        User member = newUser("role6_member");
        User admin = newUser("role6_admin");
        GroupDto group = newGroup(owner, "Role6 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(member.getId(), group.getId(), admin.getId(), GroupRole.MEMBER));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 7. MEMBER cannot remove others.
    @Test
    void member_cannotRemoveOthers() {
        User owner = newUser("role7_owner");
        User member = newUser("role7_member");
        User other = newUser("role7_other");
        GroupDto group = newGroup(owner, "Role7 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        addRawMember(group.getId(), other, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.removeMember(member.getId(), group.getId(), other.getId()));
        assertEquals("NO_REMOVE_PERMISSION", ex.getCode());
    }

    // 8. ADMIN cannot promote to OWNER (blocked entirely -- only OWNER may call changeRole at all).
    @Test
    void admin_cannotPromoteToOwner() {
        User owner = newUser("role8_owner");
        User admin = newUser("role8_admin");
        User member = newUser("role8_member");
        GroupDto group = newGroup(owner, "Role8 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(admin.getId(), group.getId(), member.getId(), GroupRole.OWNER));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 9. ADMIN cannot demote OWNER.
    @Test
    void admin_cannotDemoteOwner() {
        User owner = newUser("role9_owner");
        User admin = newUser("role9_admin");
        GroupDto group = newGroup(owner, "Role9 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(admin.getId(), group.getId(), owner.getId(), GroupRole.ADMIN));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // 10. ADMIN cannot remove OWNER.
    @Test
    void admin_cannotRemoveOwner() {
        User owner = newUser("role10_owner");
        User admin = newUser("role10_admin");
        GroupDto group = newGroup(owner, "Role10 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.removeMember(admin.getId(), group.getId(), owner.getId()));
        assertEquals("CANNOT_REMOVE_OWNER", ex.getCode());
    }

    // 11. an invalid target role (OWNER) is rejected even from the OWNER actor.
    @Test
    void invalidTargetRole_rejected() {
        User owner = newUser("role11_owner");
        User member = newUser("role11_member");
        GroupDto group = newGroup(owner, "Role11 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(owner.getId(), group.getId(), member.getId(), GroupRole.OWNER));
        assertEquals("INVALID_ROLE", ex.getCode());
    }

    // 12. a forged actor id cannot become an authority -- every check re-derives the actor's role
    // from the DB for the (actorUserId, groupId) pair actually passed in, never from anything else.
    @Test
    void forgedActorId_cannotChangeRoleOrRemove() {
        User owner = newUser("role12_owner");
        User member = newUser("role12_member");
        GroupDto group = newGroup(owner, "Role12 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        long neverIssuedUserId = 987_654_322L;
        ApiException ex1 = assertThrows(ApiException.class,
                () -> groupService.changeRole(neverIssuedUserId, group.getId(), member.getId(), GroupRole.ADMIN));
        assertEquals("NOT_GROUP_MEMBER", ex1.getCode());

        ApiException ex2 = assertThrows(ApiException.class,
                () -> groupService.removeMember(neverIssuedUserId, group.getId(), member.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex2.getCode());
    }

    // 13. a forged role cannot grant authority -- UpdateMemberRoleRequestDto only ever carries the
    // *requested* role, never an actor's claimed role; GroupAuthorizationService always re-derives
    // the actor's real role from the DB (see GroupAuthorizationServiceTest#forgedRole_cannotGrantAuthority
    // for the underlying primitive this depends on).
    @Test
    void forgedRole_cannotGrantChangeRoleAuthority() {
        User owner = newUser("role13_owner");
        User plainMember = newUser("role13_member");
        User target = newUser("role13_target");
        GroupDto group = newGroup(owner, "Role13 Group");
        addRawMember(group.getId(), plainMember, GroupRole.MEMBER);
        addRawMember(group.getId(), target, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.changeRole(plainMember.getId(), group.getId(), target.getId(), GroupRole.ADMIN));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    // ==================== REMOVE TESTS ====================

    // 14. OWNER removes MEMBER.
    @Test
    void owner_removesMember() {
        User owner = newUser("rem14_owner");
        User member = newUser("rem14_member");
        GroupDto group = newGroup(owner, "Remove14 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), member.getId()).orElseThrow().getDeletedAt());
    }

    // 15. OWNER removes ADMIN.
    @Test
    void owner_removesAdmin() {
        User owner = newUser("rem15_owner");
        User admin = newUser("rem15_admin");
        GroupDto group = newGroup(owner, "Remove15 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        groupService.removeMember(owner.getId(), group.getId(), admin.getId());

        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), admin.getId()).orElseThrow().getDeletedAt());
    }

    // 16. ADMIN removes MEMBER when permitted.
    @Test
    void admin_removesMember_whenPermitted() {
        User owner = newUser("rem16_owner");
        User admin = newUser("rem16_admin");
        User member = newUser("rem16_member");
        GroupDto group = newGroup(owner, "Remove16 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.removeMember(admin.getId(), group.getId(), member.getId());

        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), member.getId()).orElseThrow().getDeletedAt());
    }

    // 17. an unauthorized ADMIN removal (targeting another admin) is rejected.
    @Test
    void unauthorizedAdminRemoval_rejected() {
        User owner = newUser("rem17_owner");
        User admin1 = newUser("rem17_admin1");
        User admin2 = newUser("rem17_admin2");
        GroupDto group = newGroup(owner, "Remove17 Group");
        addRawMember(group.getId(), admin1, GroupRole.ADMIN);
        addRawMember(group.getId(), admin2, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.removeMember(admin1.getId(), group.getId(), admin2.getId()));
        assertEquals("ADMIN_CANNOT_REMOVE_ADMIN", ex.getCode());
    }

    // 18, 19. a removed member becomes inactive and cannot access the group afterward.
    @Test
    void removedMember_becomesInactiveAndLosesGroupAccess() {
        User owner = newUser("rem1819_owner");
        User member = newUser("rem1819_member");
        GroupDto group = newGroup(owner, "Remove1819 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        assertEquals(GroupAuthorizationService.MembershipState.INACTIVE,
                groupAuthorizationService.resolveMembershipState(group.getId(), member.getId()));
        assertFalse(groupAuthorizationService.canViewGroup(member.getId(), group.getId()));
        ApiException ex = assertThrows(ApiException.class, () -> groupService.getGroupDetails(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        ApiException exMembers = assertThrows(ApiException.class, () -> groupService.getGroupMembers(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", exMembers.getCode());
    }

    // 20. a removed member cannot subscribe to the group's WebSocket topic (Stage 0's
    // conversation-type-agnostic SUBSCRIBE check correctly picks up the now-inactive row).
    @Test
    void removedMember_cannotSubscribeToGroupConversation() {
        User owner = newUser("rem20_owner");
        User member = newUser("rem20_member");
        GroupDto group = newGroup(owner, "Remove20 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        var subscribeBeforeRemoval = subscribeFrame("/topic/conversation/" + group.getId(), member);
        assertDoesNotThrow(() -> webSocketAuthChannelInterceptor.preSend(subscribeBeforeRemoval, null));

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        var subscribeAfterRemoval = subscribeFrame("/topic/conversation/" + group.getId(), member);
        assertThrows(MessagingException.class, () -> webSocketAuthChannelInterceptor.preSend(subscribeAfterRemoval, null));
    }

    private org.springframework.messaging.Message<byte[]> subscribeFrame(String destination, User principalUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        com.connectx.common.security.UserPrincipal userPrincipal = com.connectx.common.security.UserPrincipal.create(principalUser);
        Authentication auth = new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());
        accessor.setUser(auth);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // 21. a removed member's messages remain intact (untouched by removal -- no message-deletion
    // side effect exists in removeMember at all; verified against a real inserted Message row
    // since group messaging itself isn't implemented yet in this stage).
    @Test
    void removedMember_messagesRemainIntact() {
        User owner = newUser("rem21_owner");
        User member = newUser("rem21_member");
        GroupDto group = newGroup(owner, "Remove21 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
        Message message = messageRepository.save(new Message(conversation, member, null, null,
                "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        Message reloaded = messageRepository.findById(message.getId()).orElseThrow();
        assertEquals("unchanged-ciphertext", reloaded.getCiphertext());
        assertEquals("unchanged-nonce", reloaded.getNonce());
        assertFalse(reloaded.isDeletedForEveryone());
    }

    // 22. removing one member does not affect other members.
    @Test
    void removedMember_doesNotAffectOtherMembers() {
        User owner = newUser("rem22_owner");
        User memberA = newUser("rem22_a");
        User memberB = newUser("rem22_b");
        GroupDto group = newGroup(owner, "Remove22 Group");
        addRawMember(group.getId(), memberA, GroupRole.MEMBER);
        addRawMember(group.getId(), memberB, GroupRole.MEMBER);

        groupService.removeMember(owner.getId(), group.getId(), memberA.getId());

        ConversationMember stillActive = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), memberB.getId()).orElseThrow();
        assertNull(stillActive.getDeletedAt());
        assertEquals(GroupRole.MEMBER, stillActive.getRole());
        assertEquals(GroupRole.OWNER, roleOf(group.getId(), owner.getId()));
    }

    // ==================== LEAVE TESTS ====================

    // 23. MEMBER can leave.
    @Test
    void member_canLeave() {
        User owner = newUser("leave23_owner");
        User member = newUser("leave23_member");
        GroupDto group = newGroup(owner, "Leave23 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        assertDoesNotThrow(() -> groupService.leaveGroup(member.getId(), group.getId()));
    }

    // 24. ADMIN can leave.
    @Test
    void admin_canLeave() {
        User owner = newUser("leave24_owner");
        User admin = newUser("leave24_admin");
        GroupDto group = newGroup(owner, "Leave24 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        assertDoesNotThrow(() -> groupService.leaveGroup(admin.getId(), group.getId()));
    }

    // 25. OWNER cannot leave while ownership transfer is unavailable (V1: unconditionally).
    @Test
    void owner_cannotLeave_whenTransferUnavailable() {
        User owner = newUser("leave25_owner");
        GroupDto group = newGroup(owner, "Leave25 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupService.leaveGroup(owner.getId(), group.getId()));
        assertEquals("OWNER_CANNOT_LEAVE", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // 26. leaving makes membership inactive.
    @Test
    void leaving_makesMembershipInactive() {
        User owner = newUser("leave26_owner");
        User member = newUser("leave26_member");
        GroupDto group = newGroup(owner, "Leave26 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.leaveGroup(member.getId(), group.getId());

        assertEquals(GroupAuthorizationService.MembershipState.INACTIVE,
                groupAuthorizationService.resolveMembershipState(group.getId(), member.getId()));
    }

    // 27. leaving does not delete messages.
    @Test
    void leaving_doesNotDeleteMessages() {
        User owner = newUser("leave27_owner");
        User member = newUser("leave27_member");
        GroupDto group = newGroup(owner, "Leave27 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
        Message message = messageRepository.save(new Message(conversation, member, null, null,
                "ECDH-P256+AES-256-GCM", "leave-ciphertext", "leave-nonce"));

        groupService.leaveGroup(member.getId(), group.getId());

        assertTrue(messageRepository.findById(message.getId()).isPresent());
    }

    // 28. leaving does not delete the group.
    @Test
    void leaving_doesNotDeleteGroup() {
        User owner = newUser("leave28_owner");
        User member = newUser("leave28_member");
        GroupDto group = newGroup(owner, "Leave28 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.leaveGroup(member.getId(), group.getId());

        assertDoesNotThrow(() -> groupService.getGroupDetails(owner.getId(), group.getId()));
        assertEquals(ConversationType.GROUP, conversationRepository.findById(group.getId()).orElseThrow().getType());
    }

    // 29. leaving does not delete connections.
    @Test
    void leaving_doesNotDeleteConnections() {
        User owner = newUser("leave29_owner");
        User member = newUser("leave29_member");
        connect(owner, member);
        GroupDto group = newGroup(owner, "Leave29 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.leaveGroup(member.getId(), group.getId());

        Long low = Math.min(owner.getId(), member.getId());
        Long high = Math.max(owner.getId(), member.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
    }

    // ==================== REJOIN TESTS ====================

    // 30. a removed user cannot silently rejoin, even connected with the (currently-default) ANYONE privacy.
    @Test
    void removedUser_cannotSilentlyRejoin() {
        User owner = newUser("rejoin30_owner");
        User member = newUser("rejoin30_member");
        connect(owner, member);
        GroupDto group = newGroup(owner, "Rejoin30 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        GroupAuthorizationService.AddMemberEvaluation evaluation =
                groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), member.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.INVITATION_REQUIRED, evaluation.getDecision());
        assertNotEquals(GroupAuthorizationService.AddMemberDecision.DIRECT_ADD, evaluation.getDecision());

        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.addMember(conversation, member, GroupRole.MEMBER, owner.getId()));
        assertEquals("GROUP_REINVITATION_REQUIRED", ex.getCode());
    }

    // 31. a previously left user cannot silently rejoin either -- symmetric with removal.
    @Test
    void previouslyLeftUser_cannotSilentlyRejoin() {
        User owner = newUser("rejoin31_owner");
        User member = newUser("rejoin31_member");
        connect(owner, member);
        GroupDto group = newGroup(owner, "Rejoin31 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.leaveGroup(member.getId(), group.getId());

        GroupAuthorizationService.AddMemberEvaluation evaluation =
                groupAuthorizationService.evaluateAddMember(owner.getId(), group.getId(), member.getId());
        assertEquals(GroupAuthorizationService.AddMemberDecision.INVITATION_REQUIRED, evaluation.getDecision());
    }

    // 32, 33. re-entry requires a fresh invitation, and accepting it restores active membership.
    @Test
    void reEntry_requiresInvitationAndAcceptanceRestoresMembership() {
        User owner = newUser("rejoin3233_owner");
        User member = newUser("rejoin3233_member");
        connect(owner, member);
        GroupDto group = newGroup(owner, "Rejoin3233 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        CreateGroupInvitationResponseDto invited = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(member.getId()));
        assertEquals("INVITATION_SENT", invited.getOutcome());

        groupInvitationService.acceptInvitation(member.getId(), invited.getInvitation().getId());

        assertEquals(GroupAuthorizationService.MembershipState.ACTIVE_MEMBER,
                groupAuthorizationService.resolveMembershipState(group.getId(), member.getId()));
    }

    // 34. a rejoined former ADMIN returns as MEMBER, not ADMIN -- stale privileges do not survive removal.
    @Test
    void rejoinedFormerAdmin_returnsAsMember() {
        User owner = newUser("rejoin34_owner");
        User admin = newUser("rejoin34_admin");
        connect(owner, admin);
        GroupDto group = newGroup(owner, "Rejoin34 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        groupService.removeMember(owner.getId(), group.getId(), admin.getId());

        CreateGroupInvitationResponseDto invited = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(admin.getId()));
        groupInvitationService.acceptInvitation(admin.getId(), invited.getInvitation().getId());

        assertEquals(GroupRole.MEMBER, roleOf(group.getId(), admin.getId()));
    }

    // 35. a rejoined user can access the group after acceptance.
    @Test
    void rejoinedUser_canAccessGroupAfterAcceptance() {
        User owner = newUser("rejoin35_owner");
        User member = newUser("rejoin35_member");
        connect(owner, member);
        GroupDto group = newGroup(owner, "Rejoin35 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        CreateGroupInvitationResponseDto invited = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(member.getId()));
        groupInvitationService.acceptInvitation(member.getId(), invited.getInvitation().getId());

        assertDoesNotThrow(() -> groupService.getGroupDetails(member.getId(), group.getId()));
        List<ConversationMemberDto> members = groupService.getGroupMembers(member.getId(), group.getId());
        assertTrue(members.stream().anyMatch(m -> m.getUser().getId().equals(member.getId())));
    }

    // ==================== INVITATION CONSISTENCY ====================

    // 36, 37. removing a user cancels any other stale PENDING invitation for that exact
    // (group, user) pair, so it can't later be accepted to bypass the removal decision. Engineered
    // scenario: an invitation is created while NOT connected (stays PENDING); the actor then
    // connects with the target and invites again, this time resolving to DIRECT_ADD (a different
    // code path that never touches the first invitation) -- leaving invitation #1 PENDING even
    // though the target is now genuinely active. Removing the target must clean that up.
    @Test
    void removingUser_cancelsStalePendingInvitation_andItCannotBypassRemoval() {
        User owner = newUser("stale36_owner");
        User target = newUser("stale36_target");
        GroupDto group = newGroup(owner, "Stale36 Group");

        CreateGroupInvitationResponseDto firstInvite = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("INVITATION_SENT", firstInvite.getOutcome());
        Long staleInvitationId = firstInvite.getInvitation().getId();

        connect(owner, target);
        CreateGroupInvitationResponseDto secondAttempt = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("DIRECT_ADDED", secondAttempt.getOutcome());
        assertEquals(GroupAuthorizationService.MembershipState.ACTIVE_MEMBER,
                groupAuthorizationService.resolveMembershipState(group.getId(), target.getId()));

        GroupInvitation staleBeforeRemoval = groupInvitationRepository.findById(staleInvitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.PENDING, staleBeforeRemoval.getStatus(), "precondition: the stale invitation is still PENDING before removal");

        groupService.removeMember(owner.getId(), group.getId(), target.getId());

        GroupInvitation staleAfterRemoval = groupInvitationRepository.findById(staleInvitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.CANCELLED, staleAfterRemoval.getStatus(), "removal must cancel the stale pending invitation");

        // 37. the (now-cancelled) stale invitation cannot be accepted to bypass the removal.
        ApiException ex = assertThrows(ApiException.class,
                () -> groupInvitationService.acceptInvitation(target.getId(), staleInvitationId));
        assertEquals("INVITATION_NOT_PENDING", ex.getCode());
        assertEquals(GroupAuthorizationService.MembershipState.INACTIVE,
                groupAuthorizationService.resolveMembershipState(group.getId(), target.getId()));
    }

    // ==================== REGRESSION ====================

    // 43. DIRECT conversation creation still works.
    @Test
    void directConversationCreation_stillWorks() {
        User a = newUser("regress43_a");
        User b = newUser("regress43_b");
        connect(a, b);

        ConversationDto direct = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals("DIRECT", direct.getType());
    }

    // 45. legacy DIRECT conversations still work.
    @Test
    void legacyDirectConversations_stillWork() {
        User a = newUser("regress45_a");
        User b = newUser("regress45_b");
        Conversation legacyDirect = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, a));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, b));

        ConversationDto reopened = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals(legacyDirect.getId(), reopened.getId());
    }
}
