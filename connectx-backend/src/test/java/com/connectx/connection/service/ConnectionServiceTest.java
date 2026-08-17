package com.connectx.connection.service;

import com.connectx.block.dto.UserBlockDto;
import com.connectx.block.repository.UserBlockRepository;
import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.dto.UserConnectionDto;
import com.connectx.connection.repository.ConnectionRequestRepository;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 connection-request backend: service-layer coverage of the request lifecycle
 * (send/accept/reject/cancel), authorization boundaries, and non-interference with existing
 * DIRECT conversations. Runs against real MySQL like the rest of this codebase's tests (see
 * application-test.yml) -- no H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionServiceTest {

    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConnectionRequestRepository connectionRequestRepository;
    @Autowired
    private UserConnectionRepository userConnectionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private BlockService blockService;
    @Autowired
    private UserBlockRepository userBlockRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    // 1. authenticated user can send request
    @Test
    void sendRequest_createsPendingRequestAttributedToCaller() {
        User a = newUser("send_a");
        User b = newUser("send_b");

        ConnectionRequestDto result = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        assertEquals("PENDING", result.getStatus());
        assertEquals(a.getId(), result.getRequesterId());
        assertEquals(b.getId(), result.getRecipientId());
        assertTrue(connectionRequestRepository.findById(result.getId()).isPresent());
    }

    // 3. user cannot request themselves
    @Test
    void sendRequest_rejectsSelfRequest() {
        User a = newUser("self_a");

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(a.getId())));
        assertEquals("SELF_REQUEST", ex.getCode());
    }

    // 4. duplicate pending request rejected (sequential, non-race case -- see
    // ConnectionRequestRaceIntegrationTest for the concurrent variant)
    @Test
    void sendRequest_rejectsDuplicatePendingRequest() {
        User a = newUser("dup_a");
        User b = newUser("dup_b");

        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId())));
        assertEquals("REQUEST_ALREADY_PENDING", ex.getCode());
    }

    // 5. reverse-direction request handled correctly
    @Test
    void sendRequest_rejectsReverseDirectionWhilePending() {
        User a = newUser("rev_a");
        User b = newUser("rev_b");

        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(a.getId())));
        assertEquals("REQUEST_ALREADY_PENDING", ex.getCode());
    }

    // 11. requester cannot forge requester identity -- SendConnectionRequestDto structurally
    // carries only recipientId, so the requester is always whatever caller id the (authenticated,
    // controller-derived) service call was made with, never client-supplied.
    @Test
    void sendRequest_requesterIsAlwaysTheAuthenticatedCaller() {
        User a = newUser("attr_a");
        User b = newUser("attr_b");
        User c = newUser("attr_c");

        ConnectionRequestDto abRequest = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        assertEquals(a.getId(), abRequest.getRequesterId(), "requester must be the caller, not derived from the request body");

        ConnectionRequestDto acRequest = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(c.getId()));
        assertEquals(a.getId(), acRequest.getRequesterId());
    }

    // 6. recipient can accept
    @Test
    void acceptRequest_recipientCanAcceptAndConnectionIsCreated() {
        User a = newUser("acc_a");
        User b = newUser("acc_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ConnectionRequestDto accepted = connectionService.acceptRequest(b.getId(), request.getId());

        assertEquals("ACCEPTED", accepted.getStatus());
        assertNotNull(accepted.getRespondedAt());

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));

        List<UserConnectionDto> aConnections = connectionService.getMyConnections(a.getId());
        assertTrue(aConnections.stream().anyMatch(c -> c.getConnectedUserId().equals(b.getId())));
        List<UserConnectionDto> bConnections = connectionService.getMyConnections(b.getId());
        assertTrue(bConnections.stream().anyMatch(c -> c.getConnectedUserId().equals(a.getId())));
    }

    // 7. non-recipient cannot accept
    @Test
    void acceptRequest_nonRecipientCannotAccept() {
        User a = newUser("nonacc_a");
        User b = newUser("nonacc_b");
        User c = newUser("nonacc_c");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.acceptRequest(c.getId(), request.getId()));
        assertEquals("FORBIDDEN", ex.getCode());

        // Requester attempting to accept their own outgoing request must also be rejected --
        // only the recipient decides.
        ApiException selfAcceptEx = assertThrows(ApiException.class,
                () -> connectionService.acceptRequest(a.getId(), request.getId()));
        assertEquals("FORBIDDEN", selfAcceptEx.getCode());
    }

    // 8. recipient can reject
    @Test
    void rejectRequest_recipientCanReject() {
        User a = newUser("rej_a");
        User b = newUser("rej_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ConnectionRequestDto rejected = connectionService.rejectRequest(b.getId(), request.getId());

        assertEquals("REJECTED", rejected.getStatus());
        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high),
                "a rejected request must never create a connection");
    }

    // 9. non-recipient cannot reject
    @Test
    void rejectRequest_nonRecipientCannotReject() {
        User a = newUser("nonrej_a");
        User b = newUser("nonrej_b");
        User c = newUser("nonrej_c");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.rejectRequest(c.getId(), request.getId()));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    // Requester can cancel their own pending request; nobody else can.
    @Test
    void cancelRequest_onlyRequesterCanCancel() {
        User a = newUser("cancel_a");
        User b = newUser("cancel_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.cancelRequest(b.getId(), request.getId()));
        assertEquals("FORBIDDEN", ex.getCode());

        ConnectionRequestDto cancelled = connectionService.cancelRequest(a.getId(), request.getId());
        assertEquals("CANCELLED", cancelled.getStatus());

        // A new request between the same pair after CANCELLED must be allowed (preserves the
        // audit trail as a new row rather than reusing/blocking on the old one).
        ConnectionRequestDto newRequest = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        assertEquals("PENDING", newRequest.getStatus());
        assertNotEquals(request.getId(), newRequest.getId());
    }

    // 10. accepted connection cannot be duplicated
    @Test
    void sendRequest_rejectsRequestBetweenAlreadyConnectedUsers() {
        User a = newUser("already_a");
        User b = newUser("already_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId())));
        assertEquals("ALREADY_CONNECTED", ex.getCode());

        ApiException exReverse = assertThrows(ApiException.class,
                () -> connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(a.getId())));
        assertEquals("ALREADY_CONNECTED", exReverse.getCode());
    }

    // 12/13/14. Existing DIRECT conversations, their messages, and message ciphertext/nonce must
    // be completely unaffected by the new connection-request system operating alongside them.
    // Stage 1 does not touch ConversationService, MessageService, or any DIRECT-related code --
    // this proves that in practice, not just by inspection.
    @Test
    void existingDirectConversationAndCiphertextUnaffectedByConnectionActivity() {
        User a = newUser("direct_a");
        User b = newUser("direct_b");
        User c = newUser("direct_c");

        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        Message message = messageRepository.save(new Message(
                direct, a, null, null, "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        // Exercise the new connection system in full: send, accept, and a second unrelated
        // pending request -- none of this should touch the conversation/message rows above.
        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(c.getId()));
        connectionService.acceptRequest(c.getId(), req.getId());
        connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(c.getId()));

        Optional<Message> reloaded = messageRepository.findById(message.getId());
        assertTrue(reloaded.isPresent(), "existing message must still exist");
        assertEquals("unchanged-ciphertext", reloaded.get().getCiphertext(), "ciphertext must not be modified");
        assertEquals("unchanged-nonce", reloaded.get().getNonce(), "nonce must not be modified");
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.get().getEncryptionAlgorithm());

        ConversationDto conversationDto = conversationService.getConversationById(a.getId(), direct.getId());
        assertEquals(direct.getId(), conversationDto.getId());
        assertEquals("DIRECT", conversationDto.getType());
        assertEquals(2, conversationDto.getMembers().size());
    }

    // ── Remove Connection (mini-stage) ──────────────────────────────────────────────

    // 1. connected users can remove their connection
    @Test
    void removeConnection_connectedUsersCanRemoveTheirConnection() {
        User a = newUser("remove_a");
        User b = newUser("remove_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));

        connectionService.removeConnection(a.getId(), b.getId());

        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
        assertTrue(connectionService.getMyConnections(a.getId()).isEmpty());
        assertTrue(connectionService.getMyConnections(b.getId()).isEmpty());
    }

    // 2. user A cannot remove user B <-> C's connection -- the canonical-pair lookup is scoped to
    // the caller by construction, so a forged/unrelated target ID can never reach another pair's row.
    @Test
    void removeConnection_userCannotRemoveUnrelatedUsersConnection() {
        User a = newUser("unrel_a");
        User b = newUser("unrel_b");
        User c = newUser("unrel_c");
        ConnectionRequestDto request = connectionService.sendRequest(b.getId(), new SendConnectionRequestDto(c.getId()));
        connectionService.acceptRequest(c.getId(), request.getId());

        Long low = Math.min(b.getId(), c.getId());
        Long high = Math.max(b.getId(), c.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));

        ApiException exViaB = assertThrows(ApiException.class, () -> connectionService.removeConnection(a.getId(), b.getId()));
        assertEquals("CONNECTION_NOT_FOUND", exViaB.getCode());
        ApiException exViaC = assertThrows(ApiException.class, () -> connectionService.removeConnection(a.getId(), c.getId()));
        assertEquals("CONNECTION_NOT_FOUND", exViaC.getCode());

        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high),
                "unrelated B<->C connection must be completely untouched");
    }

    // 3. removing a nonexistent connection returns the correct error
    @Test
    void removeConnection_neverConnectedUsersReturnsNotFound() {
        User a = newUser("notfound_a");
        User b = newUser("notfound_b");

        ApiException ex = assertThrows(ApiException.class, () -> connectionService.removeConnection(a.getId(), b.getId()));
        assertEquals("CONNECTION_NOT_FOUND", ex.getCode());
    }

    // A second removal of the same pair (sequential proxy for the concurrent-double-removal case)
    // must land on the same not-found path rather than silently no-op'ing or affecting anything else.
    @Test
    void removeConnection_secondRemovalAttemptReturnsNotFound() {
        User a = newUser("doubleremove_a");
        User b = newUser("doubleremove_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        connectionService.removeConnection(a.getId(), b.getId());

        ApiException ex = assertThrows(ApiException.class, () -> connectionService.removeConnection(a.getId(), b.getId()));
        assertEquals("CONNECTION_NOT_FOUND", ex.getCode());
    }

    // 4. self-removal is rejected
    @Test
    void removeConnection_rejectsSelfTargeting() {
        User a = newUser("selfremove_a");

        ApiException ex = assertThrows(ApiException.class, () -> connectionService.removeConnection(a.getId(), a.getId()));
        assertEquals("CANNOT_REMOVE_SELF", ex.getCode());
    }

    // 5/6/7. removing a connection must not delete the existing DIRECT conversation, its messages,
    // or touch any E2EE-relevant field on them.
    @Test
    void removeConnection_doesNotAffectExistingDirectConversationMessagesOrE2ee() {
        User a = newUser("removeconv_a");
        User b = newUser("removeconv_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        Message message = messageRepository.save(new Message(
                direct, a, null, null, "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        connectionService.removeConnection(a.getId(), b.getId());

        Optional<Message> reloaded = messageRepository.findById(message.getId());
        assertTrue(reloaded.isPresent(), "existing message must still exist after connection removal");
        assertEquals("unchanged-ciphertext", reloaded.get().getCiphertext());
        assertEquals("unchanged-nonce", reloaded.get().getNonce());
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.get().getEncryptionAlgorithm());

        ConversationDto conversationDto = conversationService.getConversationById(a.getId(), direct.getId());
        assertEquals(direct.getId(), conversationDto.getId());
        assertEquals("DIRECT", conversationDto.getType());
        assertEquals(2, conversationDto.getMembers().size());

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
    }

    // 8. after removal, a new connection request between the same pair can be created again
    @Test
    void removeConnection_allowsNewConnectionRequestAfterward() {
        User a = newUser("reconnect_a");
        User b = newUser("reconnect_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        connectionService.removeConnection(a.getId(), b.getId());

        ConnectionRequestDto newRequest = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        assertEquals("PENDING", newRequest.getStatus());
        assertNotEquals(request.getId(), newRequest.getId());
    }

    // 9. block relationship remains completely independent of connection removal
    @Test
    void removeConnection_doesNotAffectBlockRelationship() {
        User a = newUser("blockindep_a");
        User b = newUser("blockindep_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), request.getId());

        connectionService.removeConnection(a.getId(), b.getId());

        assertFalse(userBlockRepository.findByBlockerIdAndBlockedId(a.getId(), b.getId()).isPresent(),
                "removing a connection must never itself create a block");

        UserBlockDto block = blockService.blockUser(a.getId(), b.getId());
        assertEquals(b.getId(), block.getBlockedUserId(), "blocking must still work normally after connection removal");
    }
}
