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
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.service.MessageService;
import com.connectx.push.entity.UserPushSubscription;
import com.connectx.push.repository.UserPushSubscriptionRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 5: group message authorization, persistence, and real-time delivery -- entirely
 * through the existing MessageService/Message/MessageRepository/WebSocket pipeline (no parallel
 * "group messaging" architecture; see MessageService#sendMessage's new GROUP branch). Same
 * real-MySQL, service-layer conventions as every prior Groups suite, plus the real-embedded-
 * HTTP-server push-verification technique already proven in MessagePushPreviewTest for the
 * removed-member delivery-exclusion tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupMessagingTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupAuthorizationService groupAuthorizationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private UserPushSubscriptionRepository pushSubscriptionRepository;
    @Autowired
    private UserRepository userRepository;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

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
        return textDto(conversationId, ciphertext, 1);
    }

    private SendMessageRequestDto textDto(Long conversationId, String ciphertext, int groupKeyVersion) {
        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(conversationId);
        dto.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        dto.setCiphertext(ciphertext);
        dto.setNonce("nonce-" + System.nanoTime());
        dto.setGroupKeyVersion(groupKeyVersion);
        return dto;
    }

    // ==================== AUTHORIZATION ====================

    // OWNER can always send, regardless of who_can_send_messages.
    @Test
    void owner_canSendMessage_underEitherPolicy() {
        User owner = newUser("send_owner1");
        GroupDto group = newGroup(owner, "Send Owner Group");
        assertDoesNotThrow(() -> messageService.sendMessage(owner.getId(), textDto(group.getId(), "hello-owner")));

        UpdateGroupSettingsRequestDto adminsOnly = new UpdateGroupSettingsRequestDto();
        adminsOnly.setWhoCanSendMessages("ADMINS_ONLY");
        groupService.updateSettings(owner.getId(), group.getId(), adminsOnly);
        assertDoesNotThrow(() -> messageService.sendMessage(owner.getId(), textDto(group.getId(), "hello-owner-again")));
    }

    // ADMIN can send under EVERYONE and ADMINS_ONLY.
    @Test
    void admin_canSendMessage_underEitherPolicy() {
        User owner = newUser("send_owner2");
        User admin = newUser("send_admin2");
        GroupDto group = newGroup(owner, "Send Admin Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        assertDoesNotThrow(() -> messageService.sendMessage(admin.getId(), textDto(group.getId(), "hi-1")));

        UpdateGroupSettingsRequestDto adminsOnly = new UpdateGroupSettingsRequestDto();
        adminsOnly.setWhoCanSendMessages("ADMINS_ONLY");
        groupService.updateSettings(owner.getId(), group.getId(), adminsOnly);
        assertDoesNotThrow(() -> messageService.sendMessage(admin.getId(), textDto(group.getId(), "hi-2")));
    }

    // MEMBER can send under the default EVERYONE policy.
    @Test
    void member_canSendMessage_underEveryonePolicy() {
        User owner = newUser("send_owner3");
        User member = newUser("send_member3");
        GroupDto group = newGroup(owner, "Send Member Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        assertDoesNotThrow(() -> messageService.sendMessage(member.getId(), textDto(group.getId(), "hi-member")));
    }

    // MEMBER is rejected once the group is switched to ADMINS_ONLY.
    @Test
    void member_cannotSendMessage_underAdminsOnlyPolicy() {
        User owner = newUser("send_owner4");
        User member = newUser("send_member4");
        GroupDto group = newGroup(owner, "Send AdminsOnly Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        UpdateGroupSettingsRequestDto adminsOnly = new UpdateGroupSettingsRequestDto();
        adminsOnly.setWhoCanSendMessages("ADMINS_ONLY");
        groupService.updateSettings(owner.getId(), group.getId(), adminsOnly);

        ApiException ex = assertThrows(ApiException.class,
                () -> messageService.sendMessage(member.getId(), textDto(group.getId(), "should-fail")));
        assertEquals("SEND_NOT_PERMITTED", ex.getCode());
    }

    // A non-member cannot send. Rejected by MessageService's pre-existing, conversation-type-
    // agnostic "does any membership row exist at all" gate (unchanged this stage) before the new
    // GROUP-specific active-membership/policy check is ever reached -- that gate is what
    // removedMember_cannotSendMessage below exercises instead, since a removed member's row still
    // exists (soft-deleted).
    @Test
    void nonMember_cannotSendMessage() {
        User owner = newUser("send_owner5");
        User outsider = newUser("send_outsider5");
        GroupDto group = newGroup(owner, "Send NonMember Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> messageService.sendMessage(outsider.getId(), textDto(group.getId(), "nope")));
        assertEquals("NOT_CONVERSATION_MEMBER", ex.getCode());
    }

    // A removed/left (inactive) member cannot send, even though their ConversationMember row
    // still exists (soft-deleted) -- confirms Stage 5's GROUP branch requires ACTIVE membership,
    // unlike the plain membership-row lookup DIRECT's own send path uses for its "restore my
    // hidden view" behavior.
    @Test
    void removedMember_cannotSendMessage() {
        User owner = newUser("send_owner6");
        User member = newUser("send_member6");
        GroupDto group = newGroup(owner, "Send Removed Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> messageService.sendMessage(member.getId(), textDto(group.getId(), "should-fail-too")));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // A user who voluntarily left cannot send either.
    @Test
    void leftMember_cannotSendMessage() {
        User owner = newUser("send_owner7");
        User member = newUser("send_member7");
        GroupDto group = newGroup(owner, "Send Left Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.leaveGroup(member.getId(), group.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> messageService.sendMessage(member.getId(), textDto(group.getId(), "should-fail-again")));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // Sending into a DIRECT conversation id as if it were a group id is rejected the same way a
    // non-existent group is -- confirms the server derives conversation type itself, never trusts
    // a client-implied type.
    @Test
    void directConversationId_rejectedByGroupPathIsIrrelevant_sendStillWorksNormally() {
        // Sanity: sending into an actual DIRECT conversation must still work exactly as before --
        // this is really a DIRECT-regression check placed here since it's the closest sibling of
        // the GROUP-authorization tests above.
        User a = newUser("send_direct_a");
        User b = newUser("send_direct_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(com.connectx.conversation.entity.ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        assertDoesNotThrow(() -> messageService.sendMessage(a.getId(), textDto(direct.getId(), "direct-hello")));
    }

    // ==================== BLOCKING / CONNECTION NON-INTERFERENCE ====================

    // A blocked pair can both still send/receive group messages -- group membership is the
    // authorization boundary, not pairwise blocking (docs/CONNECTX_GROUP_ARCHITECTURE.md's
    // approved blocking semantics: a block never removes shared group membership).
    @Test
    void blockedPair_canBothStillSendGroupMessages() {
        User owner = newUser("block_owner");
        User member = newUser("block_member");
        GroupDto group = newGroup(owner, "Blocked Pair Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        blockService.blockUser(member.getId(), owner.getId());

        assertDoesNotThrow(() -> messageService.sendMessage(owner.getId(), textDto(group.getId(), "from-owner-despite-block")));
        assertDoesNotThrow(() -> messageService.sendMessage(member.getId(), textDto(group.getId(), "from-member-despite-block")));
    }

    // Group messaging never requires a connection between sender and any other member -- see also
    // MessageConnectionAuthorizationTest#groupConversation_isNotSubjectToConnectionCheck for the
    // three-completely-unconnected-members case.
    @Test
    void groupMessage_neverRequiresConnectionToOtherMembers() {
        User owner = newUser("noconn_owner");
        User member = newUser("noconn_member");
        GroupDto group = newGroup(owner, "No Connection Required Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        // Deliberately never connected.

        assertDoesNotThrow(() -> messageService.sendMessage(member.getId(), textDto(group.getId(), "no-connection-needed")));
    }

    // ==================== PERSISTENCE / E2EE OPACITY ====================

    // The message persists via the existing Message entity/table, with ciphertext/nonce stored
    // exactly as received -- the server never decrypts or transforms them.
    @Test
    void groupMessage_persistsWithOpaqueCiphertext() {
        User owner = newUser("persist_owner");
        GroupDto group = newGroup(owner, "Persist Group");

        MessageDto sent = messageService.sendMessage(owner.getId(),
                textDto(group.getId(), "genuinely-opaque-ciphertext-bytes"));

        Message reloaded = messageRepository.findById(sent.getId()).orElseThrow();
        assertEquals(group.getId(), reloaded.getConversation().getId());
        assertEquals("genuinely-opaque-ciphertext-bytes", reloaded.getCiphertext());
        assertEquals(owner.getId(), reloaded.getSenderUser().getId());
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.getEncryptionAlgorithm());
    }

    // ==================== DELIVERY EXCLUSION ====================

    // A removed member receives no push notification for a group message sent after their
    // removal -- proves MessageService#sendMessage's broadcastRecipients filter (which the
    // per-user /queue/messages WebSocket delivery and the push-notification fan-out both iterate
    // from the same loop) genuinely excludes them, not just that authorization rejects their own
    // sends. Same real-embedded-HttpServer push-capture technique as MessagePushPreviewTest.
    @Test
    void removedMember_receivesNoPushForMessagesSentAfterRemoval() throws Exception {
        User owner = newUser("push_owner");
        User removedMember = newUser("push_removed");
        GroupDto group = newGroup(owner, "Push Exclusion Group");
        addRawMember(group.getId(), removedMember, GroupRole.MEMBER);

        AtomicInteger hitCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            hitCount.incrementAndGet();
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/push";

        UserPushSubscription sub = new UserPushSubscription();
        sub.setUser(removedMember);
        sub.setEndpoint(endpoint);
        sub.setP256dhKey("BDummyP256dhKeyBDummyP256dhKeyBDummyP256dhKeyBDummyP256dhKeyABC");
        sub.setAuthKey("DummyAuthKey123");
        pushSubscriptionRepository.save(sub);

        groupService.removeMember(owner.getId(), group.getId(), removedMember.getId());

        // Removal rotates the group key (see GroupService#markKeyRotationRequired) -- version 2.
        messageService.sendMessage(owner.getId(), textDto(group.getId(), "after-removal", 2));

        Thread.sleep(500);
        assertEquals(0, hitCount.get(), "a removed member must receive no push notification for a message sent after their removal");
    }

    // ==================== REGRESSION ====================

    // 50-member capacity is unaffected by message sending -- confirms no membership-count logic
    // was accidentally introduced into the send path.
    @Test
    void sendingMessages_doesNotAffectMemberCapacityAccounting() {
        User owner = newUser("cap_owner");
        GroupDto group = newGroup(owner, "Capacity Unaffected Group");
        long before = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());

        for (int i = 0; i < 5; i++) {
            messageService.sendMessage(owner.getId(), textDto(group.getId(), "msg-" + i));
        }

        long after = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());
        assertEquals(before, after);
        assertFalse(groupAuthorizationService.isGroupFull(group.getId()));
    }
}
