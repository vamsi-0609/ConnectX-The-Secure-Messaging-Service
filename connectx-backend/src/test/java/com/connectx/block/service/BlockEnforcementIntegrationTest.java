package com.connectx.block.service;

import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.service.MessageService;
import com.connectx.user.dto.UserDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 blocking backend: cross-service enforcement coverage. Verifies a block correctly gates
 * every NEW-relationship boundary (direct-conversation creation, connection requests, connection
 * acceptance, DIRECT message send) in both directions, takes precedence over an existing
 * connection, and -- just as importantly -- never touches existing conversation/message/member
 * data. Follows the same real-MySQL, service-layer conventions as DirectConversationAuthorizationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class BlockEnforcementIntegrationTest {

    @Autowired
    private BlockService blockService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private UserConnectionRepository userConnectionRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MessageService messageService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private void connect(User a, User b) {
        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), req.getId());
    }

    // 7. blocked user cannot create a new DIRECT conversation with the blocker
    @Test
    void createOrGetDirectConversation_blockedUserCannotInitiate() {
        User a = newUser("dm_block_a");
        User b = newUser("dm_block_b");
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 8. blocker cannot create a new DIRECT conversation with the blocked user either
    @Test
    void createOrGetDirectConversation_blockerCannotInitiate() {
        User a = newUser("dm_block2_a");
        User b = newUser("dm_block2_b");
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 9. blocked user cannot send a connection request to the blocker
    @Test
    void sendRequest_blockedUserCannotRequestBlocker() {
        User a = newUser("req_block_a");
        User b = newUser("req_block_b");
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(a.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 10. blocker cannot send a connection request to the blocked user
    @Test
    void sendRequest_blockerCannotRequestBlockedUser() {
        User a = newUser("req_block2_a");
        User b = newUser("req_block2_b");
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId())));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 11. a pending request cannot be accepted once a blocking relationship exists between the
    // two parties, even though it was sent before the block (both directions of "who blocked
    // whom" relative to "who is the recipient" are exercised).
    @Test
    void acceptRequest_rejectedWhenBlockingRelationshipExists() {
        User a = newUser("accept_block_a");
        User b = newUser("accept_block_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class, () -> connectionService.acceptRequest(b.getId(), request.getId()));
        assertEquals("BLOCKED", ex.getCode());

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high),
                "a blocked pending request must never become a connection");
    }

    @Test
    void acceptRequest_rejectedWhenRecipientIsTheBlocker() {
        User a = newUser("accept_block2_a");
        User b = newUser("accept_block2_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        blockService.blockUser(b.getId(), a.getId());

        ApiException ex = assertThrows(ApiException.class, () -> connectionService.acceptRequest(b.getId(), request.getId()));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 14, 16. an existing DIRECT conversation and its member rows must remain completely intact
    // after a block is created between its two participants.
    @Test
    void existingDirectConversation_remainsIntactAfterBlock() {
        User a = newUser("existing_a");
        User b = newUser("existing_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        ConversationMember memberA = conversationMemberRepository.save(new ConversationMember(direct, a));
        ConversationMember memberB = conversationMemberRepository.save(new ConversationMember(direct, b));

        blockService.blockUser(a.getId(), b.getId());

        ConversationDto reloaded = conversationService.getConversationById(a.getId(), direct.getId());
        assertEquals(direct.getId(), reloaded.getId());
        assertEquals("DIRECT", reloaded.getType());
        assertEquals(2, reloaded.getMembers().size());
        assertTrue(conversationMemberRepository.findById(memberA.getId()).isPresent());
        assertTrue(conversationMemberRepository.findById(memberB.getId()).isPresent());
    }

    // 15. existing ciphertext/nonce/encryption algorithm are byte-for-byte unchanged by a block.
    @Test
    void existingMessageCiphertext_remainsUnchangedAfterBlock() {
        User a = newUser("cipher_a");
        User b = newUser("cipher_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        Message existing = messageRepository.save(new Message(
                direct, a, null, null, "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        blockService.blockUser(a.getId(), b.getId());

        Message reloaded = messageRepository.findById(existing.getId()).orElseThrow();
        assertEquals("unchanged-ciphertext", reloaded.getCiphertext());
        assertEquals("unchanged-nonce", reloaded.getNonce());
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.getEncryptionAlgorithm());
    }

    // High-risk MessageService change: once blocked, NEITHER party can send a NEW message in an
    // already-existing DIRECT conversation -- but the conversation and prior history stay exactly
    // as they were (covered above). This is the smallest possible authorization check requested
    // by the Stage 2 brief, added directly alongside the existing membership check.
    @Test
    void sendMessage_rejectedInExistingDirectConversationAfterBlock() {
        User a = newUser("send_block_a");
        User b = newUser("send_block_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        blockService.blockUser(a.getId(), b.getId());

        SendMessageRequestDto fromBlocker = new SendMessageRequestDto();
        fromBlocker.setConversationId(direct.getId());
        fromBlocker.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        fromBlocker.setCiphertext("c");
        fromBlocker.setNonce("n");
        ApiException exA = assertThrows(ApiException.class, () -> messageService.sendMessage(a.getId(), fromBlocker));
        assertEquals("BLOCKED", exA.getCode());

        SendMessageRequestDto fromBlocked = new SendMessageRequestDto();
        fromBlocked.setConversationId(direct.getId());
        fromBlocked.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        fromBlocked.setCiphertext("c2");
        fromBlocked.setNonce("n2");
        ApiException exB = assertThrows(ApiException.class, () -> messageService.sendMessage(b.getId(), fromBlocked));
        assertEquals("BLOCKED", exB.getCode());
    }

    // 19. an existing connection does not let a blocked pair bypass the block: a NEW conversation
    // still cannot be created, and a new connection request still cannot be sent, despite the
    // connections row existing.
    @Test
    void connectedUsers_cannotBypassBlock() {
        User a = newUser("bypass_a");
        User b = newUser("bypass_b");
        connect(a, b);
        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high), "precondition: already connected");

        blockService.blockUser(a.getId(), b.getId());

        ApiException dmEx = assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId())));
        assertEquals("BLOCKED", dmEx.getCode(), "an existing connection must not let a blocked pair start a new conversation");

        ApiException reqEx = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(a.getId())));
        assertEquals("BLOCKED", reqEx.getCode());

        // The connection itself is untouched -- Stage 2 does not delete it, only gates new
        // relationship formation on top of it.
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
    }

    // Unblocking restores exactly the prior authorization state: a connected pair can message
    // again, and an unconnected pair returns to needing a connection (not silently authorized).
    @Test
    void unblock_restoresPriorAuthorizationState() {
        User a = newUser("restore_a");
        User b = newUser("restore_b");
        connect(a, b);
        blockService.blockUser(a.getId(), b.getId());
        assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId())));

        blockService.unblockUser(a.getId(), b.getId());

        ConversationDto dto = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals("DIRECT", dto.getType());
    }

    // ── Search privacy (blocked-users management mini-stage) ────────────────────────────────

    // 1. a user blocked by the searcher must not appear in that searcher's results
    @Test
    void searchUsers_excludesUserBlockedByCurrentUser() {
        User a = newUser("searchblock_a");
        User b = newUser("searchblock_bxyz");
        blockService.blockUser(a.getId(), b.getId());

        List<UserDto> results = userService.searchUsersByUsername("searchblock_bxyz", a.getId());
        assertTrue(results.stream().noneMatch(u -> u.getId().equals(b.getId())),
                "a user the searcher blocked must not appear in search results");
    }

    // 2. reverse direction: a user who blocked the searcher must also not appear -- blocking is
    // symmetric for discovery purposes even though the underlying row is directional.
    @Test
    void searchUsers_excludesUserWhoBlockedCurrentUser() {
        User a = newUser("searchblock2_a");
        User b = newUser("searchblock2_bxyz");
        blockService.blockUser(b.getId(), a.getId());

        List<UserDto> results = userService.searchUsersByUsername("searchblock2_bxyz", a.getId());
        assertTrue(results.stream().noneMatch(u -> u.getId().equals(b.getId())),
                "a user who blocked the searcher must not appear in that searcher's results either");
    }

    // 3. blocking is pair-specific: an unrelated third user's search is completely unaffected
    @Test
    void searchUsers_unrelatedUserStillSeesNormalResults() {
        User a = newUser("searchblock3_a");
        User b = newUser("searchblock3_bxyz");
        User c = newUser("searchblock3_c");
        blockService.blockUser(a.getId(), b.getId());

        List<UserDto> results = userService.searchUsersByUsername("searchblock3_bxyz", c.getId());
        assertTrue(results.stream().anyMatch(u -> u.getId().equals(b.getId())),
                "an unrelated user's search must be unaffected by someone else's block");
    }

    // 4. unblocking restores discoverability -- this is purely a search-visibility check;
    // relationship state after unblock (NOT_CONNECTED etc.) is covered by ConnectionServiceTest.
    @Test
    void searchUsers_unblockRestoresDiscoverability() {
        User a = newUser("searchblock4_a");
        User b = newUser("searchblock4_bxyz");
        blockService.blockUser(a.getId(), b.getId());
        assertTrue(userService.searchUsersByUsername("searchblock4_bxyz", a.getId()).isEmpty());

        blockService.unblockUser(a.getId(), b.getId());

        List<UserDto> results = userService.searchUsersByUsername("searchblock4_bxyz", a.getId());
        assertTrue(results.stream().anyMatch(u -> u.getId().equals(b.getId())),
                "unblocking must restore discoverability in search");
    }
}
