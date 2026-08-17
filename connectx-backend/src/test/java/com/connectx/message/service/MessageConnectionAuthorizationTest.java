package com.connectx.message.service;

import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Existing DIRECT chat bypasses current connection authorization" fix: conversation membership
 * alone was previously sufficient to send into a DIRECT conversation (as long as no active block
 * existed) even with zero current connection -- e.g. a legacy pre-Stage-1 pair, or a pair whose
 * connection had since been removed (block, or explicit remove-connection). MessageService#send
 * Message now additionally requires a currently-CONNECTED pair for DIRECT sends, scoped exactly
 * like the existing BLOCKED check (GROUP conversations are untouched -- see
 * groupConversation_isNotSubjectToConnectionCheck).
 */
@SpringBootTest
@ActiveProfiles("test")
class MessageConnectionAuthorizationTest {

    @Autowired
    private MessageService messageService;
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
    private UserConnectionRepository userConnectionRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private void connect(User a, User b) {
        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), req.getId());
    }

    private Conversation newDirectConversation(User a, User b) {
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, a));
        conversationMemberRepository.save(new ConversationMember(conversation, b));
        return conversation;
    }

    private SendMessageRequestDto textDto(Long conversationId, String ciphertext) {
        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(conversationId);
        dto.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        dto.setCiphertext(ciphertext);
        dto.setNonce("nonce-" + System.nanoTime());
        return dto;
    }

    // 1, 12. connected users with an existing DIRECT conversation can send normally.
    @Test
    void connectedUsers_canSendInExistingDirectConversation() {
        User a = newUser("connsend_a");
        User b = newUser("connsend_b");
        connect(a, b);
        Conversation conv = newDirectConversation(a, b);

        MessageDto sent = messageService.sendMessage(a.getId(), textDto(conv.getId(), "ct-1"));

        assertEquals("ct-1", sent.getCiphertext());
        assertTrue(messageRepository.findById(sent.getId()).isPresent());
    }

    // 2, 8. connection removed (via remove-connection, not blocking) -> send rejected with
    // NOT_CONNECTED, and the rejected message is never persisted.
    @Test
    void connectionRemoved_sendRejectedAndNeverPersisted() {
        User a = newUser("connremoved_a");
        User b = newUser("connremoved_b");
        connect(a, b);
        Conversation conv = newDirectConversation(a, b);

        connectionService.removeConnection(a.getId(), b.getId());

        long before = messageRepository.count();
        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(b.getId(), textDto(conv.getId(), "ct-2")));
        assertEquals("NOT_CONNECTED", ex.getCode());
        assertEquals(before, messageRepository.count(), "a rejected send must not persist any message row");
    }

    // 3. blocked pair with an existing DIRECT conversation -> send rejected (BLOCKED takes
    // priority over NOT_CONNECTED, matching the existing check ordering).
    @Test
    void blockedPair_sendRejected() {
        User a = newUser("blocksend_a");
        User b = newUser("blocksend_b");
        connect(a, b);
        Conversation conv = newDirectConversation(a, b);
        blockService.blockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(a.getId(), textDto(conv.getId(), "ct-3")));
        assertEquals("BLOCKED", ex.getCode());
    }

    // 4. block then unblock: the connection removed by blocking is not restored by unblocking.
    @Test
    void blockThenUnblock_connectionRemainsAbsent() {
        User a = newUser("blockunblock_a");
        User b = newUser("blockunblock_b");
        connect(a, b);
        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());

        blockService.blockUser(a.getId(), b.getId());
        blockService.unblockUser(a.getId(), b.getId());

        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
    }

    // 5. block then unblock, with an existing DIRECT conversation -> send still rejected
    // (NOT_CONNECTED now, since the active block is gone but no connection was restored).
    @Test
    void blockThenUnblock_sendStillRejected() {
        User a = newUser("blockunblocksend_a");
        User b = newUser("blockunblocksend_b");
        connect(a, b);
        Conversation conv = newDirectConversation(a, b);

        blockService.blockUser(a.getId(), b.getId());
        blockService.unblockUser(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(b.getId(), textDto(conv.getId(), "ct-5")));
        assertEquals("NOT_CONNECTED", ex.getCode());
    }

    // 6. a fresh connection request, once accepted, restores send capability on the same
    // pre-existing conversation -- no duplicate conversation, the same one is reused.
    @Test
    void newConnectionAccepted_sendSucceedsAgain() {
        User a = newUser("reconnectsend_a");
        User b = newUser("reconnectsend_b");
        connect(a, b);
        Conversation conv = newDirectConversation(a, b);
        connectionService.removeConnection(a.getId(), b.getId());
        assertThrows(ApiException.class, () -> messageService.sendMessage(a.getId(), textDto(conv.getId(), "ct-6a")));

        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), req.getId());

        MessageDto sent = messageService.sendMessage(a.getId(), textDto(conv.getId(), "ct-6b"));
        assertEquals("ct-6b", sent.getCiphertext());
        assertEquals(conv.getId(), sent.getConversationId());
    }

    // 7. a legacy DIRECT conversation that never had a connection row at all (pre-dates the
    // connection system) -- send is rejected exactly the same as a removed-connection pair.
    @Test
    void legacyConversationWithNoConnection_sendRejected() {
        User a = newUser("legacysend_a");
        User b = newUser("legacysend_b");
        Conversation conv = newDirectConversation(a, b);
        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high), "precondition: never connected");

        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(a.getId(), textDto(conv.getId(), "ct-7")));
        assertEquals("NOT_CONNECTED", ex.getCode());
    }

    // 9. rejected sends are never broadcast -- not independently testable without mocking
    // SimpMessagingTemplate (no precedent for that in this codebase's test suite), but structurally
    // guaranteed: sendMessage's authorization block (membership/block/connection) runs before any
    // persistence, and the WebSocket broadcast is scheduled via afterCommitExecutor.runAfterCommit
    // only after persistence -- an exception thrown during authorization never reaches either.
    // connectionRemoved_sendRejectedAndNeverPersisted (test 2/8 above) is the closest direct proof:
    // if the message row itself was never created, nothing downstream of it (including the
    // broadcast, which reads fields off the saved entity) could have run either.

    // 10. E2EE ciphertext/nonce/algorithm on a pre-existing message are untouched by a rejected
    // send attempt on the same conversation.
    @Test
    void rejectedSend_leavesExistingCiphertextUntouched() {
        User a = newUser("ciphersend_a");
        User b = newUser("ciphersend_b");
        Conversation conv = newDirectConversation(a, b);
        Message existing = messageRepository.save(new Message(
                conv, a, null, null, "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        assertThrows(ApiException.class, () -> messageService.sendMessage(b.getId(), textDto(conv.getId(), "rejected-ct")));

        Message reloaded = messageRepository.findById(existing.getId()).orElseThrow();
        assertEquals("unchanged-ciphertext", reloaded.getCiphertext());
        assertEquals("unchanged-nonce", reloaded.getNonce());
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.getEncryptionAlgorithm());
    }

    // 11. GROUP conversations are completely unaffected by the new connection check -- it is
    // scoped to ConversationType.DIRECT only, exactly like the pre-existing BLOCKED check. Not
    // testing any actual group business logic (none exists yet) -- only that a GROUP-typed
    // conversation's send path never reaches the new DIRECT-only authorization block.
    @Test
    void groupConversation_isNotSubjectToConnectionCheck() {
        User a = newUser("groupsend_a");
        User b = newUser("groupsend_b");
        User c = newUser("groupsend_c");
        // Deliberately never connected to each other.
        Conversation group = conversationRepository.save(new Conversation(ConversationType.GROUP));
        conversationMemberRepository.save(new ConversationMember(group, a));
        conversationMemberRepository.save(new ConversationMember(group, b));
        conversationMemberRepository.save(new ConversationMember(group, c));

        MessageDto sent = messageService.sendMessage(a.getId(), textDto(group.getId(), "group-ct"));

        assertEquals("group-ct", sent.getCiphertext());
        assertTrue(messageRepository.findById(sent.getId()).isPresent());
    }
}
