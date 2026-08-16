package com.connectx.message.service;

import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageReaction;
import com.connectx.message.repository.MessageReactionRepository;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the fix for the "concurrent reaction toggle can throw an uncaught 500" bug
 * (L-05): two requests from the SAME user reacting to the SAME message at almost the
 * exact same instant must both succeed (no exception surfaced to either caller), and
 * must never leave more than one MessageReaction row behind.
 * <p>
 * This exercises the real fix end-to-end against a real MySQL instance (not H2 or a
 * mock) -- specifically, that {@code tryInsertReactionInNewTransaction}'s
 * {@code REQUIRES_NEW} propagation actually isolates a losing insert's constraint
 * violation from the caller's own transaction, since that's the part of the fix that
 * can't be verified by reading the code alone (it depends on Spring's AOP proxy and the
 * database's real transaction/locking behavior).
 */
@SpringBootTest
@ActiveProfiles("test")
class ReactionRaceIntegrationTest {

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
    @Autowired
    private MessageReactionRepository messageReactionRepository;

    @Test
    void concurrentReactionsFromSameUserOnSameMessageNeverThrowOrDuplicate() throws Exception {
        User user = userRepository.save(new User(
                "race_user_" + System.nanoTime(), "race_" + System.nanoTime() + "@test.com", "hash", "Race Tester"));
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, user));

        int attempts = 15; // several attempts to reliably provoke a true race, not just one lucky/unlucky timing
        for (int i = 0; i < attempts; i++) {
            Message msg = messageRepository.save(new Message(conversation, user, null, null, "NONE", "", ""));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> failureA = new AtomicReference<>();
            AtomicReference<Throwable> failureB = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Runnable reactA = () -> {
                ready.countDown();
                await(go);
                try {
                    messageService.addOrUpdateReaction(user.getId(), msg.getId(), "👍"); // 👍
                } catch (Throwable t) {
                    failureA.set(t);
                }
            };
            Runnable reactB = () -> {
                ready.countDown();
                await(go);
                try {
                    messageService.addOrUpdateReaction(user.getId(), msg.getId(), "❤️"); // ❤️
                } catch (Throwable t) {
                    failureB.set(t);
                }
            };

            pool.submit(reactA);
            pool.submit(reactB);
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
            go.countDown(); // release both at once to maximize the chance of a genuine race
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both reactions must complete within the timeout");

            assertNull(failureA.get(), "concurrent reaction A must not throw (attempt " + i + "): " + failureA.get());
            assertNull(failureB.get(), "concurrent reaction B must not throw (attempt " + i + "): " + failureB.get());

            List<MessageReaction> reactions = messageReactionRepository.findByMessageIdWithUsers(msg.getId());
            assertEquals(1, reactions.size(),
                    "exactly one reaction row must exist for (message, user) after the race, attempt " + i);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
