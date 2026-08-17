package com.connectx.conversation.service;

import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.repository.ConversationRepository;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test items 17/18: concurrent createOrGetDirectConversation calls for the same pair must never
 * produce duplicate DIRECT conversations, whether the pair is authorized or not.
 * <p>
 * Unlike the Stage 1 connections/connection_requests tables, the conversations table has no
 * DB-level unique constraint on a DIRECT pair (schema changes are out of scope for Stage 1.5), so
 * this relies on the pessimistic row lock added in ConversationService#createOrGetDirectConversation
 * (a SELECT ... FOR UPDATE against the lower-id user's existing primary key row) rather than the
 * REQUIRES_NEW + DataIntegrityViolationException pattern used elsewhere in this codebase.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectConversationRaceIntegrationTest {

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    // 17. two concurrent authorized requests must resolve to exactly one DIRECT conversation.
    @Test
    void concurrentAuthorizedCreatesResolveToExactlyOneConversation() throws Exception {
        User a = newUser("race_ok_a");
        User b = newUser("race_ok_b");
        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), req.getId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable fromA = () -> {
            ready.countDown();
            await(go);
            try {
                resultA.set(conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId())));
            } catch (Throwable t) {
                resultA.set(t);
            }
        };
        Runnable fromB = () -> {
            ready.countDown();
            await(go);
            try {
                resultB.set(conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId())));
            } catch (Throwable t) {
                resultB.set(t);
            }
        };

        pool.submit(fromA);
        pool.submit(fromB);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both attempts must complete within the timeout");

        assertInstanceOf(ConversationDto.class, resultA.get(), "A's attempt must not throw: " + resultA.get());
        assertInstanceOf(ConversationDto.class, resultB.get(), "B's attempt must not throw: " + resultB.get());
        assertEquals(((ConversationDto) resultA.get()).getId(), ((ConversationDto) resultB.get()).getId(),
                "both callers must resolve to the same conversation");

        long directConversationCount = conversationRepository.findDirectConversationBetweenUsers(a.getId(), b.getId())
                .stream().count();
        assertEquals(1, directConversationCount, "exactly one DIRECT conversation must exist for the pair after the race");
    }

    // 18. two concurrent unauthorized requests must never create a DIRECT conversation.
    @Test
    void concurrentUnauthorizedCreatesNeverCreateAConversation() throws Exception {
        User a = newUser("race_bad_a");
        User b = newUser("race_bad_b");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable fromA = () -> {
            ready.countDown();
            await(go);
            try {
                resultA.set(conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId())));
            } catch (Throwable t) {
                resultA.set(t);
            }
        };
        Runnable fromB = () -> {
            ready.countDown();
            await(go);
            try {
                resultB.set(conversationService.createOrGetDirectConversation(b.getId(), new CreateDirectConversationDto(a.getId())));
            } catch (Throwable t) {
                resultB.set(t);
            }
        };

        pool.submit(fromA);
        pool.submit(fromB);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both attempts must complete within the timeout");

        for (Object result : List.of(resultA.get(), resultB.get())) {
            ApiException ex = assertInstanceOf(ApiException.class, result, "unauthorized attempt must fail, not succeed: " + result);
            assertEquals("NOT_CONNECTED", ex.getCode());
        }

        assertFalse(conversationRepository.findDirectConversationBetweenUsers(a.getId(), b.getId()).isPresent(),
                "no DIRECT conversation may exist after two unauthorized concurrent attempts");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
