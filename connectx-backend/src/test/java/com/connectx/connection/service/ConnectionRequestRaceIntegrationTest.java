package com.connectx.connection.service;

import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.entity.ConnectionRequestStatus;
import com.connectx.connection.repository.ConnectionRequestRepository;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies test item 15 (concurrent request/acceptance does not create duplicate rows) end-to-end
 * against real MySQL, following the same isolated-REQUIRES_NEW pattern and CountDownLatch-timed
 * race style already proven in ReactionRaceIntegrationTest/StarRaceIntegrationTest.
 * <p>
 * connection_requests' partial-unique-on-PENDING constraint (uk_connreq_pending_pair) is a MySQL
 * generated-column trick that isn't expressible via plain JPA annotations (see
 * ConnectionRequest's class Javadoc) -- connectx_db has it from the Stage 0B migration script,
 * but connectx_test_db is rebuilt from entity metadata alone on every run, so it would otherwise
 * be missing here. ensureConnectionRequestPartialUniqueIndexExists below adds the identical DDL
 * directly, so this test genuinely exercises the same DB-level protection production has.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionRequestRaceIntegrationTest {

    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConnectionRequestRepository connectionRequestRepository;
    @Autowired
    private UserConnectionRepository userConnectionRepository;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void ensureConnectionRequestPartialUniqueIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            boolean columnExists;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'connection_requests' AND COLUMN_NAME = 'pending_pair_key'")) {
                rs.next();
                columnExists = rs.getInt(1) > 0;
            }
            if (!columnExists) {
                stmt.execute(
                        "ALTER TABLE connection_requests ADD COLUMN pending_pair_key VARCHAR(41) GENERATED ALWAYS AS (" +
                        "CASE WHEN status = 'PENDING' THEN CONCAT(requester_id, '_', recipient_id) ELSE NULL END) VIRTUAL, " +
                        "ADD UNIQUE KEY uk_connreq_pending_pair (pending_pair_key)");
            }
        }
    }

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    @Test
    void concurrentDuplicateRequestsForSamePairResolveToExactlyOnePendingRow() throws Exception {
        User a = newUser("race_req_a");
        User b = newUser("race_req_b");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable sendA = () -> {
            ready.countDown();
            await(go);
            try {
                resultA.set(connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId())));
            } catch (Throwable t) {
                resultA.set(t);
            }
        };
        Runnable sendB = () -> {
            ready.countDown();
            await(go);
            try {
                resultB.set(connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId())));
            } catch (Throwable t) {
                resultB.set(t);
            }
        };

        pool.submit(sendA);
        pool.submit(sendB);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both attempts must complete within the timeout");

        // Neither call may surface an unhandled exception -- one succeeds, the other must land on
        // the typed ApiException(REQUEST_ALREADY_PENDING), never a raw DataIntegrityViolationException.
        int successes = 0;
        for (Object result : List.of(resultA.get(), resultB.get())) {
            if (result instanceof ConnectionRequestDto) {
                successes++;
            } else if (result instanceof com.connectx.common.exception.ApiException apiEx) {
                assertEquals("REQUEST_ALREADY_PENDING", apiEx.getCode(),
                        "the losing side must fail with the typed conflict error, not something else: " + apiEx.getMessage());
            } else {
                fail("unexpected outcome type: " + result);
            }
        }
        assertEquals(1, successes, "exactly one of the two concurrent requests must win");

        long pendingCount = connectionRequestRepository
                .findByRecipientIdAndStatusWithUsers(b.getId(), ConnectionRequestStatus.PENDING)
                .size();
        assertEquals(1, pendingCount, "exactly one PENDING connection_requests row must exist for the pair after the race");
    }

    @Test
    void concurrentAcceptOfSameRequestNeverThrowsAndCreatesExactlyOneConnection() throws Exception {
        User a = newUser("race_acc_a");
        User b = newUser("race_acc_b");
        ConnectionRequestDto request = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable acceptA = () -> {
            ready.countDown();
            await(go);
            try {
                connectionService.acceptRequest(b.getId(), request.getId());
            } catch (Throwable t) {
                failureA.set(t);
            }
        };
        Runnable acceptB = () -> {
            ready.countDown();
            await(go);
            try {
                connectionService.acceptRequest(b.getId(), request.getId());
            } catch (Throwable t) {
                failureB.set(t);
            }
        };

        pool.submit(acceptA);
        pool.submit(acceptB);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both accepts must complete within the timeout");

        assertNull(failureA.get(), "concurrent accept A must not throw: " + failureA.get());
        assertNull(failureB.get(), "concurrent accept B must not throw: " + failureB.get());

        Long low = Math.min(a.getId(), b.getId());
        Long high = Math.max(a.getId(), b.getId());
        assertTrue(userConnectionRepository.existsByUserLowIdAndUserHighId(low, high));

        long connectionCount = userConnectionRepository.findAllForUserWithUsers(a.getId()).stream()
                .filter(c -> (c.getUserLow().getId().equals(a.getId()) && c.getUserHigh().getId().equals(b.getId()))
                        || (c.getUserLow().getId().equals(b.getId()) && c.getUserHigh().getId().equals(a.getId())))
                .count();
        assertEquals(1, connectionCount, "exactly one connection row must exist after the race, never two");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
