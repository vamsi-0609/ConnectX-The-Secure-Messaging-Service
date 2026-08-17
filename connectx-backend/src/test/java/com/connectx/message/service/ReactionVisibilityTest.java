package com.connectx.message.service;

import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the "reactions don't show up" bug reported against the running app: adding the
 * FIRST reaction from a user on a message persisted correctly (survives a page reload) but the
 * REST response and WebSocket broadcast that the UI actually renders from always reported an
 * empty reaction list.
 * <p>
 * Root cause: addOrUpdateReaction's new-reaction path inserts via a REQUIRES_NEW nested
 * transaction (insertReactionInNewTransaction, added for the earlier L-05 race fix), then reads
 * the full reaction list back in the OUTER transaction to build the response/broadcast. Under
 * MySQL's default REPEATABLE READ isolation, the outer transaction's consistent-read snapshot
 * was already established by its earlier SELECTs (message/member/user lookups), so that read
 * back doesn't see the nested transaction's own commit -- a real cross-transaction visibility
 * gap, not something a mock or H2's (different-by-default) isolation level would expose. Needs
 * real MySQL, like the other Stage 2/3 race-condition tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReactionVisibilityTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;

    @Test
    void firstReactionOnAMessageIsImmediatelyVisibleInTheReturnedDto() {
        User user = userRepository.save(new User(
                "react_vis_" + System.nanoTime(), "react_vis_" + System.nanoTime() + "@test.com", "hash", "Reaction Visibility Tester"));
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, user));
        Message msg = messageRepository.save(new Message(conversation, user, null, null, "NONE", "", ""));

        MessageDto result = messageService.addOrUpdateReaction(user.getId(), msg.getId(), "🔥");

        assertEquals(1, result.getReactions().size(),
                "the reaction just added must be present in the same call's returned DTO, not just after a later re-fetch");
        assertEquals("🔥", result.getReactions().get(0).getReaction());
        assertEquals(user.getId(), result.getReactions().get(0).getUserId());
    }

    @Test
    void secondUsersFirstReactionSeesTheFirstUsersReactionToo() {
        User userA = userRepository.save(new User(
                "react_vis_a_" + System.nanoTime(), "react_vis_a_" + System.nanoTime() + "@test.com", "hash", "Visibility A"));
        User userB = userRepository.save(new User(
                "react_vis_b_" + System.nanoTime(), "react_vis_b_" + System.nanoTime() + "@test.com", "hash", "Visibility B"));
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, userA));
        conversationMemberRepository.save(new ConversationMember(conversation, userB));
        Message msg = messageRepository.save(new Message(conversation, userA, null, null, "NONE", "", ""));

        messageService.addOrUpdateReaction(userA.getId(), msg.getId(), "👍");
        MessageDto result = messageService.addOrUpdateReaction(userB.getId(), msg.getId(), "😮");

        assertEquals(2, result.getReactions().size(), "both users' reactions must be visible after the second user's first reaction");
        assertTrue(result.getReactions().stream().anyMatch(r -> r.getUserId().equals(userA.getId()) && r.getReaction().equals("👍")));
        assertTrue(result.getReactions().stream().anyMatch(r -> r.getUserId().equals(userB.getId()) && r.getReaction().equals("😮")));
    }
}
