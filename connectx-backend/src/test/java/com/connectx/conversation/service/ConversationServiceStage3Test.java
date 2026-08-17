package com.connectx.conversation.service;

import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageUserState;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.repository.MessageUserStateRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-04 + M-05 correctness, run against real MySQL like the rest of Stage 3's tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationServiceStage3Test {

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MessageUserStateRepository messageUserStateRepository;

    @Test
    void batchedConversationListReturnsCorrectPerConversationLastMessage() {
        User me = userRepository.save(new User(
                "m04_me_" + System.nanoTime(), "m04_me_" + System.nanoTime() + "@test.com", "hash", "M04 Me"));
        User peer1 = userRepository.save(new User(
                "m04_p1_" + System.nanoTime(), "m04_p1_" + System.nanoTime() + "@test.com", "hash", "Peer 1"));
        User peer2 = userRepository.save(new User(
                "m04_p2_" + System.nanoTime(), "m04_p2_" + System.nanoTime() + "@test.com", "hash", "Peer 2"));

        Conversation convo1 = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(convo1, me));
        conversationMemberRepository.save(new ConversationMember(convo1, peer1));
        Message convo1Msg1 = messageRepository.save(new Message(convo1, me, null, null, "NONE", "c1", "n1"));
        Message convo1Msg2 = messageRepository.save(new Message(convo1, peer1, null, null, "NONE", "c2", "n2"));

        Conversation convo2 = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(convo2, me));
        conversationMemberRepository.save(new ConversationMember(convo2, peer2));
        Message convo2Msg1 = messageRepository.save(new Message(convo2, peer2, null, null, "NONE", "c3", "n3"));

        List<ConversationDto> conversations = conversationService.getUserConversations(me.getId());
        Map<Long, ConversationDto> byId = conversations.stream().collect(Collectors.toMap(ConversationDto::getId, d -> d));

        assertEquals(convo1Msg2.getId(), byId.get(convo1.getId()).getLastMessageId(),
                "conversation 1's preview must be its most recent message, not an earlier one");
        assertEquals(convo2Msg1.getId(), byId.get(convo2.getId()).getLastMessageId());
    }

    @Test
    void clearedConversationHidesMessagesSentBeforeTheClearButShowsLaterOnes() {
        User me = userRepository.save(new User(
                "m04_clear_me_" + System.nanoTime(), "m04_clear_me_" + System.nanoTime() + "@test.com", "hash", "Clear Me"));
        User peer = userRepository.save(new User(
                "m04_clear_p_" + System.nanoTime(), "m04_clear_p_" + System.nanoTime() + "@test.com", "hash", "Clear Peer"));

        Conversation convo = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        ConversationMember myMembership = conversationMemberRepository.save(new ConversationMember(convo, me));
        conversationMemberRepository.save(new ConversationMember(convo, peer));

        messageRepository.save(new Message(convo, peer, null, null, "NONE", "before-clear", "n1"));

        // Simulate "clear chat": everything up to now should stop showing as the preview.
        myMembership.setClearedAt(Instant.now());
        conversationMemberRepository.saveAndFlush(myMembership);

        List<ConversationDto> afterClearNoNewMessages = conversationService.getUserConversations(me.getId());
        ConversationDto dtoNoNewMessages = afterClearNoNewMessages.stream()
                .filter(d -> d.getId().equals(convo.getId())).findFirst().orElseThrow();
        assertEquals(null, dtoNoNewMessages.getLastMessageId(),
                "a message sent before the clear must not surface as the preview even though it's the only message");

        Message afterClearMsg = messageRepository.save(new Message(convo, peer, null, null, "NONE", "after-clear", "n2"));
        List<ConversationDto> afterClearWithNewMessage = conversationService.getUserConversations(me.getId());
        ConversationDto dtoWithNewMessage = afterClearWithNewMessage.stream()
                .filter(d -> d.getId().equals(convo.getId())).findFirst().orElseThrow();
        assertEquals(afterClearMsg.getId(), dtoWithNewMessage.getLastMessageId(),
                "a message sent after the clear must surface as the preview");
    }

    @Test
    void bulkDeleteMarksEveryMessageWithoutDuplicatingAlreadyIndividuallyDeletedOnes() {
        User me = userRepository.save(new User(
                "m05_me_" + System.nanoTime(), "m05_me_" + System.nanoTime() + "@test.com", "hash", "M05 Me"));
        User peer = userRepository.save(new User(
                "m05_peer_" + System.nanoTime(), "m05_peer_" + System.nanoTime() + "@test.com", "hash", "M05 Peer"));

        Conversation convo = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(convo, me));
        conversationMemberRepository.save(new ConversationMember(convo, peer));

        Message alreadyIndividuallyDeleted = messageRepository.save(new Message(convo, peer, null, null, "NONE", "c1", "n1"));
        Message untouched1 = messageRepository.save(new Message(convo, peer, null, null, "NONE", "c2", "n2"));
        Message untouched2 = messageRepository.save(new Message(convo, me, null, null, "NONE", "c3", "n3"));

        // "me" had already individually deleted one message before deleting the whole chat.
        messageUserStateRepository.save(new MessageUserState(alreadyIndividuallyDeleted, me));

        conversationService.deleteConversationForUser(me.getId(), convo.getId());

        assertTrue(messageUserStateRepository.existsByMessageIdAndUserId(alreadyIndividuallyDeleted.getId(), me.getId()));
        assertTrue(messageUserStateRepository.existsByMessageIdAndUserId(untouched1.getId(), me.getId()));
        assertTrue(messageUserStateRepository.existsByMessageIdAndUserId(untouched2.getId(), me.getId()));

        long stateRowsForFirstMessage = messageUserStateRepository.findAll().stream()
                .filter(s -> s.getMessage().getId().equals(alreadyIndividuallyDeleted.getId()) && s.getUser().getId().equals(me.getId()))
                .count();
        assertEquals(1, stateRowsForFirstMessage,
                "the pre-existing per-message delete state must not be duplicated by the bulk insert");
    }
}
