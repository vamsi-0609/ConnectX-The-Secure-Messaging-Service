package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.config.WebSocketAuthChannelInterceptor;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import com.connectx.group.repository.GroupInvitationRepository;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.service.MessageService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 7: owner-only group deletion and the owner-exit UX correction (owner deletes
 * instead of leaving, since ownership transfer does not exist yet). Same real-MySQL,
 * service-layer conventions as GroupMembershipServiceTest -- GroupService/
 * GroupAuthorizationService are exercised directly, no MockMvc, since forging an actor has
 * nothing to do with the HTTP layer (see GroupControllerSecurityTest for the one HTTP-layer
 * check this stage still needs).
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupDeletionServiceTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private GroupInvitationRepository groupInvitationRepository;
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

    private SendMessageRequestDto textDto(Long conversationId, String ciphertext) {
        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(conversationId);
        dto.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        dto.setCiphertext(ciphertext);
        dto.setNonce("nonce-" + System.nanoTime());
        return dto;
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

    // ==================== AUTHORIZATION ====================

    // 1. Owner can delete group.
    @Test
    void owner_canDeleteGroup() {
        User owner = newUser("del1_owner");
        GroupDto group = newGroup(owner, "Delete1 Group");

        assertDoesNotThrow(() -> groupService.deleteGroup(owner.getId(), group.getId()));
    }

    // 2. Member cannot delete group.
    @Test
    void member_cannotDeleteGroup() {
        User owner = newUser("del2_owner");
        User member = newUser("del2_member");
        GroupDto group = newGroup(owner, "Delete2 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class, () -> groupService.deleteGroup(member.getId(), group.getId()));
        assertEquals("OWNER_ONLY", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // 3. Admin cannot delete group.
    @Test
    void admin_cannotDeleteGroup() {
        User owner = newUser("del3_owner");
        User admin = newUser("del3_admin");
        GroupDto group = newGroup(owner, "Delete3 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        ApiException ex = assertThrows(ApiException.class, () -> groupService.deleteGroup(admin.getId(), group.getId()));
        assertEquals("OWNER_ONLY", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // 4. Non-member cannot delete group.
    @Test
    void nonMember_cannotDeleteGroup() {
        User owner = newUser("del4_owner");
        User outsider = newUser("del4_outsider");
        GroupDto group = newGroup(owner, "Delete4 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupService.deleteGroup(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 5. A DIRECT conversation cannot be deleted through the group endpoint.
    @Test
    void directConversation_cannotBeDeletedThroughGroupEndpoint() {
        User a = newUser("del5_a");
        User b = newUser("del5_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class, () -> groupService.deleteGroup(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), a.getId()).orElseThrow().getDeletedAt());
    }

    // 6. A forged actor/user id cannot delete a group -- role is always re-derived from the DB for
    // the (actorUserId, groupId) pair actually passed in, never trusted from a caller.
    @Test
    void forgedActorId_cannotDeleteGroup() {
        User owner = newUser("del6_owner");
        GroupDto group = newGroup(owner, "Delete6 Group");

        long neverIssuedUserId = 987_654_323L;
        ApiException ex = assertThrows(ApiException.class, () -> groupService.deleteGroup(neverIssuedUserId, group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // ==================== POST-DELETE LIFECYCLE ====================

    // 7. A deleted group cannot be opened -- not even by its former owner.
    @Test
    void deletedGroup_cannotBeOpened() {
        User owner = newUser("del7_owner");
        User member = newUser("del7_member");
        GroupDto group = newGroup(owner, "Delete7 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.deleteGroup(owner.getId(), group.getId());

        ApiException exOwner = assertThrows(ApiException.class, () -> groupService.getGroupDetails(owner.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", exOwner.getCode());
        ApiException exMember = assertThrows(ApiException.class, () -> groupService.getGroupDetails(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", exMember.getCode());
    }

    // 8. A deleted group cannot return members.
    @Test
    void deletedGroup_cannotReturnMembers() {
        User owner = newUser("del8_owner");
        User member = newUser("del8_member");
        GroupDto group = newGroup(owner, "Delete8 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.deleteGroup(owner.getId(), group.getId());

        ApiException ex = assertThrows(ApiException.class, () -> groupService.getGroupMembers(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 9. A deleted group cannot receive new messages -- from anyone, including the former owner.
    @Test
    void deletedGroup_cannotReceiveNewMessages() {
        User owner = newUser("del9_owner");
        User member = newUser("del9_member");
        GroupDto group = newGroup(owner, "Delete9 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.deleteGroup(owner.getId(), group.getId());

        ApiException exOwner = assertThrows(ApiException.class,
                () -> messageService.sendMessage(owner.getId(), textDto(group.getId(), "after-delete-owner")));
        assertEquals("NOT_GROUP_MEMBER", exOwner.getCode());
        ApiException exMember = assertThrows(ApiException.class,
                () -> messageService.sendMessage(member.getId(), textDto(group.getId(), "after-delete-member")));
        assertEquals("NOT_GROUP_MEMBER", exMember.getCode());
    }

    // 10. A deleted group cannot receive new invitations -- the former owner is no longer an active
    // member, so createInvitation fails at the same requireActiveMember gate as every other
    // post-delete operation.
    @Test
    void deletedGroup_cannotAcceptNewInvitations() {
        User owner = newUser("del10_owner");
        User target = newUser("del10_target");
        GroupDto group = newGroup(owner, "Delete10 Group");

        groupService.deleteGroup(owner.getId(), group.getId());

        ApiException ex = assertThrows(ApiException.class, () -> groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 11. Existing pending invitations become invalid -- cannot be accepted, rejected, or cancelled
    // once the group is deleted.
    @Test
    void existingPendingInvitations_becomeInvalidAfterDelete() {
        User owner = newUser("del11_owner");
        User target = newUser("del11_target");
        GroupDto group = newGroup(owner, "Delete11 Group");

        CreateGroupInvitationResponseDto invited = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = invited.getInvitation().getId();

        groupService.deleteGroup(owner.getId(), group.getId());

        GroupInvitation cancelled = groupInvitationRepository.findById(invitationId).orElseThrow();
        assertEquals(GroupInvitationStatus.CANCELLED, cancelled.getStatus());

        ApiException exAccept = assertThrows(ApiException.class, () -> groupInvitationService.acceptInvitation(target.getId(), invitationId));
        assertEquals("INVITATION_NOT_PENDING", exAccept.getCode());
        ApiException exReject = assertThrows(ApiException.class, () -> groupInvitationService.rejectInvitation(target.getId(), invitationId));
        assertEquals("INVITATION_NOT_PENDING", exReject.getCode());
        // cancelInvitation checks the actor's canInvite permission before the invitation's own
        // status -- since the (former) owner is no longer an active member post-delete, this fails
        // one check earlier than accept/reject (NO_INVITE_PERMISSION, not INVITATION_NOT_PENDING).
        // Still a safe failure either way: no path here can touch the deleted group.
        ApiException exCancel = assertThrows(ApiException.class, () -> groupInvitationService.cancelInvitation(owner.getId(), invitationId));
        assertEquals("NO_INVITE_PERMISSION", exCancel.getCode());
        assertEquals(GroupAuthorizationService.MembershipState.NEVER_MEMBER,
                groupAuthorizationService.resolveMembershipState(group.getId(), target.getId()));
    }

    // 12. Existing members (and the owner) lose active membership.
    @Test
    void existingMembers_loseActiveMembershipAfterDelete() {
        User owner = newUser("del12_owner");
        User admin = newUser("del12_admin");
        User member = newUser("del12_member");
        GroupDto group = newGroup(owner, "Delete12 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.deleteGroup(owner.getId(), group.getId());

        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), admin.getId()).orElseThrow().getDeletedAt());
        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), member.getId()).orElseThrow().getDeletedAt());
        assertEquals(GroupAuthorizationService.MembershipState.INACTIVE,
                groupAuthorizationService.resolveMembershipState(group.getId(), owner.getId()));
    }

    // 13. WebSocket subscription to a deleted group's topic is rejected for every former member,
    // reusing the existing Stage 0 SUBSCRIBE authorization -- no second mechanism.
    @Test
    void deletedGroup_websocketSubscriptionRejected() {
        User owner = newUser("del13_owner");
        User member = newUser("del13_member");
        GroupDto group = newGroup(owner, "Delete13 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        var subscribeBeforeDelete = subscribeFrame("/topic/conversation/" + group.getId(), member);
        assertDoesNotThrow(() -> webSocketAuthChannelInterceptor.preSend(subscribeBeforeDelete, null));

        groupService.deleteGroup(owner.getId(), group.getId());

        var ownerSubscribeAfterDelete = subscribeFrame("/topic/conversation/" + group.getId(), owner);
        assertThrows(MessagingException.class, () -> webSocketAuthChannelInterceptor.preSend(ownerSubscribeAfterDelete, null));
        var memberSubscribeAfterDelete = subscribeFrame("/topic/conversation/" + group.getId(), member);
        assertThrows(MessagingException.class, () -> webSocketAuthChannelInterceptor.preSend(memberSubscribeAfterDelete, null));
    }

    // ==================== LEAVE REGRESSION (unchanged) ====================

    // 14. Member can still leave a normal active group.
    @Test
    void member_canStillLeaveNormalActiveGroup() {
        User owner = newUser("del14_owner");
        User member = newUser("del14_member");
        GroupDto group = newGroup(owner, "Delete14 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        assertDoesNotThrow(() -> groupService.leaveGroup(member.getId(), group.getId()));
        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), member.getId()).orElseThrow().getDeletedAt());
        assertDoesNotThrow(() -> groupService.getGroupDetails(owner.getId(), group.getId()));
    }

    // 15. Admin can still leave a normal active group.
    @Test
    void admin_canStillLeaveNormalActiveGroup() {
        User owner = newUser("del15_owner");
        User admin = newUser("del15_admin");
        GroupDto group = newGroup(owner, "Delete15 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        assertDoesNotThrow(() -> groupService.leaveGroup(admin.getId(), group.getId()));
        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), admin.getId()).orElseThrow().getDeletedAt());
        assertDoesNotThrow(() -> groupService.getGroupDetails(owner.getId(), group.getId()));
    }

    // 16. Owner cannot leave while ownership transfer does not exist -- unchanged by this stage; the
    // owner's only exit is delete.
    @Test
    void owner_stillCannotLeave_whenTransferUnavailable() {
        User owner = newUser("del16_owner");
        GroupDto group = newGroup(owner, "Delete16 Group");

        ApiException ex = assertThrows(ApiException.class, () -> groupService.leaveGroup(owner.getId(), group.getId()));
        assertEquals("OWNER_CANNOT_LEAVE", ex.getCode());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId()).orElseThrow().getDeletedAt());
    }

    // 17. The 50-member limit remains unchanged by this stage.
    @Test
    void memberLimit_remainsUnchanged() {
        assertEquals(50, GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS);
    }

    // 18. DIRECT conversations remain completely unaffected by group deletion changes.
    @Test
    void directConversations_remainUnaffected() {
        User a = newUser("del18_a");
        User b = newUser("del18_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        assertDoesNotThrow(() -> messageService.sendMessage(a.getId(), textDto(direct.getId(), "direct-unaffected")));
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), a.getId()).orElseThrow().getDeletedAt());
        assertNull(conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), b.getId()).orElseThrow().getDeletedAt());
    }
}
