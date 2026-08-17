package com.connectx.message.service;

import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageStar;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.repository.MessageStarRepository;
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
 * M-06: starMessage used to be a plain exists-check-then-insert with no database-level
 * uniqueness guarantee, so two near-simultaneous star requests from the same user on the same
 * message (e.g. a double-tap) could both pass the exists check and both insert, leaving
 * duplicate message_stars rows. Mirrors ReactionRaceIntegrationTest's approach: real MySQL
 * (not H2/mocks), since what's actually being verified is that the REQUIRES_NEW-isolated
 * insert + unique constraint combination behaves correctly under real transaction/locking
 * semantics, not just that the Java code reads correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
class StarRaceIntegrationTest {

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
    private MessageStarRepository messageStarRepository;

    @Test
    void concurrentStarsFromSameUserOnSameMessageNeverThrowOrDuplicate() throws Exception {
        User user = userRepository.save(new User(
                "star_race_" + System.nanoTime(), "star_race_" + System.nanoTime() + "@test.com", "hash", "Star Race Tester"));
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, user));

        int attempts = 15;
        for (int i = 0; i < attempts; i++) {
            Message msg = messageRepository.save(new Message(conversation, user, null, null, "NONE", "", ""));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> failureA = new AtomicReference<>();
            AtomicReference<Throwable> failureB = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Runnable starA = () -> {
                ready.countDown();
                await(go);
                try {
                    messageService.starMessage(user.getId(), msg.getId());
                } catch (Throwable t) {
                    failureA.set(t);
                }
            };
            Runnable starB = () -> {
                ready.countDown();
                await(go);
                try {
                    messageService.starMessage(user.getId(), msg.getId());
                } catch (Throwable t) {
                    failureB.set(t);
                }
            };

            pool.submit(starA);
            pool.submit(starB);
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both star calls must complete within the timeout");

            assertNull(failureA.get(), "concurrent star A must not throw (attempt " + i + "): " + failureA.get());
            assertNull(failureB.get(), "concurrent star B must not throw (attempt " + i + "): " + failureB.get());

            List<MessageStar> stars = messageStarRepository.findByMessageIdAndUserId(msg.getId(), user.getId())
                    .map(List::of)
                    .orElse(List.of());
            assertEquals(1, stars.size(), "exactly one star row must exist for (message, user) after the race, attempt " + i);
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
