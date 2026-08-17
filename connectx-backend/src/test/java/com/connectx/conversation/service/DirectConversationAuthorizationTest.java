package com.connectx.conversation.service;

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
 * Stage 1.5: wires the existing DIRECT conversation creation flow
 * (ConversationService#createOrGetDirectConversation) to the Stage 1 connection system. Follows
 * the same real-MySQL, service-layer conventions as ConnectionServiceTest -- no mocks, no MockMvc
 * (this codebase has no positive-auth MockMvc tests anywhere; identity/connection-state forgery is
 * proven the same way Stage 1 proved requester-forgery-proofing: by inspection of the DTOs plus a
 * service-layer test, since CreateDirectConversationDto structurally carries only the target
 * userId -- there is no field to forge a requester id or a "connected" flag into).
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectConversationAuthorizationTest {

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
    private UserService userService;
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

    // 1 & 15. connected users (via full request -> accept lifecycle) can create a new DIRECT conversation
    @Test
    void createOrGetDirectConversation_connectedUsersCanCreateNewConversation() {
        User a = newUser("conn_a");
        User b = newUser("conn_b");
        connect(a, b);

        ConversationDto dto = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));

        assertEquals("DIRECT", dto.getType());
        assertEquals(2, dto.getMembers().size());
        assertTrue(conversationRepository.findById(dto.getId()).isPresent());
    }

    // 2. unconnected users cannot create a new DIRECT conversation
    @Test
    void createOrGetDirectConversation_unconnectedUsersCannotCreateNewConversation() {
        User a = newUser("unconn_a");
        User b = newUser("unconn_b");

        ApiException ex = assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId())));
        assertEquals("NOT_CONNECTED", ex.getCode());
        assertFalse(conversationRepository.findDirectConversationBetweenUsers(a.getId(), b.getId()).isPresent());
    }

    // 3. self-chat still rejected
    @Test
    void createOrGetDirectConversation_rejectsSelfChat() {
        User a = newUser("self_a");

        ApiException ex = assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(a.getId())));
        assertEquals("SELF_CHAT_NOT_ALLOWED", ex.getCode());
    }

    // 4 & 16. a pre-existing DIRECT conversation with no connections row works, and continues to
    // work on repeated opens without ever requiring a connection to exist.
    @Test
    void createOrGetDirectConversation_preExistingConversationWorksWithoutConnectionRow() {
        User a = newUser("legacy_a");
        User b = newUser("legacy_b");

        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high), "precondition: no connection exists");

        ConversationDto first = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals(direct.getId(), first.getId());

        // Re-open again -- must still work, still no connection required.
        ConversationDto second = conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId()));
        assertEquals(direct.getId(), second.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));
    }

    // 5 & 6. Updated for the "existing DIRECT chat bypasses current connection authorization" fix:
    // opening a pre-existing, connection-less conversation and reading its history still works and
    // never touches existing ciphertext/nonce/encryption-algorithm, but MessageService now requires
    // a currently-CONNECTED pair to send a NEW message even into an existing DIRECT conversation --
    // conversation membership alone is no longer sufficient. See MessageService#sendMessage's DIRECT
    // authorization block and MessageConnectionAuthorizationTest for the full matrix of cases.
    @Test
    void existingConversationHistory_preservedButNewMessageRejectedWithoutConnection() {
        User a = newUser("msg_a");
        User b = newUser("msg_b");

        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        Message existing = messageRepository.save(new Message(
                direct, a, null, null, "ECDH-P256+AES-256-GCM", "unchanged-ciphertext", "unchanged-nonce"));

        // Opening the conversation via the gated flow must not touch the pre-existing message.
        conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        Message reloaded = messageRepository.findById(existing.getId()).orElseThrow();
        assertEquals("unchanged-ciphertext", reloaded.getCiphertext());
        assertEquals("unchanged-nonce", reloaded.getNonce());
        assertEquals("ECDH-P256+AES-256-GCM", reloaded.getEncryptionAlgorithm());

        // Sending a brand-new message on this connection-less conversation must now be rejected --
        // membership alone is no longer sufficient authorization to send.
        SendMessageRequestDto sendDto = new SendMessageRequestDto();
        sendDto.setConversationId(direct.getId());
        sendDto.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        sendDto.setCiphertext("new-ciphertext");
        sendDto.setNonce("new-nonce");
        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(b.getId(), sendDto));
        assertEquals("NOT_CONNECTED", ex.getCode());
    }

    // 7, 8, 9. search remains usable for an unconnected user, and never creates a conversation or
    // a connection as a side effect.
    @Test
    void search_worksForUnconnectedUserWithoutCreatingConversationOrConnection() {
        User a = newUser("search_a");
        User b = newUser("search_bxyz");

        List<UserDto> results = userService.searchUsersByUsername("search_bxyz", a.getId());
        assertTrue(results.stream().anyMatch(u -> u.getId().equals(b.getId())), "search must still find the unconnected user");

        assertFalse(conversationRepository.findDirectConversationBetweenUsers(a.getId(), b.getId()).isPresent(),
                "search must never create a conversation");
        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertFalse(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high),
                "search must never create a connection");
    }

    // 10. requester identity cannot be forged -- CreateDirectConversationDto structurally carries
    // only the target userId, so the created conversation's membership is always exactly
    // {authenticated caller, target}, never a third party.
    @Test
    void createOrGetDirectConversation_membershipIsAlwaysCallerAndTarget() {
        User a = newUser("forge_a");
        User b = newUser("forge_b");
        connect(a, b);

        ConversationDto dto = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));

        List<Long> memberIds = dto.getMembers().stream().map(m -> m.getUser().getId()).toList();
        assertEquals(2, memberIds.size());
        assertTrue(memberIds.contains(a.getId()));
        assertTrue(memberIds.contains(b.getId()));
    }

    // 11. there is no "connected" flag on CreateDirectConversationDto to forge -- authorization is
    // derived purely from server-side connection state. Demonstrated by exhausting every
    // connection-adjacent state that falls short of an accepted connection and confirming none of
    // them are treated as authorization.
    @Test
    void createOrGetDirectConversation_onlyAnAcceptedConnectionRowAuthorizes() {
        User a = newUser("state_a");
        User b = newUser("state_b");

        // No request at all yet.
        assertEquals("NOT_CONNECTED", assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()))).getCode());

        // 12 & 14. a merely PENDING request (from either side) does not authorize.
        ConnectionRequestDto pending = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        assertEquals("NOT_CONNECTED", assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()))).getCode());
        assertEquals("NOT_CONNECTED", assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId()))).getCode());

        // 13. a rejected request does not authorize.
        connectionService.rejectRequest(b.getId(), pending.getId());
        assertEquals("NOT_CONNECTED", assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()))).getCode());

        // A cancelled request does not authorize either.
        ConnectionRequestDto second = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.cancelRequest(a.getId(), second.getId());
        assertEquals("NOT_CONNECTED", assertThrows(ApiException.class,
                () -> conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()))).getCode());

        // Only an ACCEPTED connection authorizes.
        ConnectionRequestDto third = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), third.getId());
        ConversationDto created = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals("DIRECT", created.getType());
    }
}
