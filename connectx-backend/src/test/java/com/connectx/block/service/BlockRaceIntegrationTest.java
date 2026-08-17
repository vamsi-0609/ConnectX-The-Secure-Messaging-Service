package com.connectx.block.service;

import com.connectx.block.repository.UserBlockRepository;
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
 * Test items 17 & 18: concurrent block operations must never leave more than one row for a given
 * (blocker, blocked) pair, and a block/unblock race must resolve safely (no exceptions, no
 * duplicate/corrupted rows). Follows the same CountDownLatch-timed race style already proven in
 * ConnectionRequestRaceIntegrationTest/ReactionRaceIntegrationTest, against real MySQL.
 * <p>
 * Unlike connection_requests' partial-unique-on-PENDING index, user_blocks' uk_user_blocks_pair
 * constraint is a plain two-column unique index -- fully expressible via UserBlock's
 * {@code @Table(uniqueConstraints = ...)} annotation, so it is already present in
 * connectx_test_db via ddl-auto=create-drop with no extra raw-SQL setup needed here.
 */
@SpringBootTest
@ActiveProfiles("test")
class BlockRaceIntegrationTest {

    @Autowired
    private BlockService blockService;
    @Autowired
    private UserBlockRepository userBlockRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 17. two simultaneous POST /blocks/{userId} for the same pair must result in exactly one row.
    @Test
    void concurrentDuplicateBlocksForSamePairResolveToExactlyOneRow() throws Exception {
        User a = newUser("race_block_a");
        User b = newUser("race_block_b");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable block1 = () -> {
            ready.countDown();
            await(go);
            try {
                blockService.blockUser(a.getId(), b.getId());
            } catch (Throwable t) {
                failure1.set(t);
            }
        };
        Runnable block2 = () -> {
            ready.countDown();
            await(go);
            try {
                blockService.blockUser(a.getId(), b.getId());
            } catch (Throwable t) {
                failure2.set(t);
            }
        };

        pool.submit(block1);
        pool.submit(block2);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both attempts must complete within the timeout");

        assertNull(failure1.get(), "concurrent block must never surface an unhandled exception: " + failure1.get());
        assertNull(failure2.get(), "concurrent block must never surface an unhandled exception: " + failure2.get());

        List<com.connectx.block.entity.UserBlock> rows = userBlockRepository.findAllByBlockerIdWithBlocked(a.getId());
        long matching = rows.stream().filter(r -> r.getBlocked().getId().equals(b.getId())).count();
        assertEquals(1, matching, "exactly one user_blocks row must exist for the pair after the race, never two");
    }

    // 18. a concurrent block + unblock for the same pair must resolve safely: no exception from
    // either side, and never more than one row left behind.
    @Test
    void concurrentBlockAndUnblockResolveSafely() throws Exception {
        User a = newUser("race_bu_a");
        User b = newUser("race_bu_b");
        blockService.blockUser(a.getId(), b.getId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> blockFailure = new AtomicReference<>();
        AtomicReference<Throwable> unblockFailure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable reBlock = () -> {
            ready.countDown();
            await(go);
            try {
                blockService.blockUser(a.getId(), b.getId());
            } catch (Throwable t) {
                blockFailure.set(t);
            }
        };
        Runnable unblock = () -> {
            ready.countDown();
            await(go);
            try {
                blockService.unblockUser(a.getId(), b.getId());
            } catch (Throwable t) {
                unblockFailure.set(t);
            }
        };

        pool.submit(reBlock);
        pool.submit(unblock);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads must reach the starting line");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "both operations must complete within the timeout");

        assertNull(blockFailure.get(), "concurrent (re-)block must never surface an unhandled exception: " + blockFailure.get());
        assertNull(unblockFailure.get(), "concurrent unblock must never surface an unhandled exception: " + unblockFailure.get());

        long remaining = userBlockRepository.findAllByBlockerIdWithBlocked(a.getId()).stream()
                .filter(r -> r.getBlocked().getId().equals(b.getId()))
                .count();
        assertTrue(remaining == 0 || remaining == 1, "at most one row may ever exist for the pair, got " + remaining);
    }
}
