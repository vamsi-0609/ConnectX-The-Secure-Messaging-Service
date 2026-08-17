package com.connectx.block.service;

import com.connectx.block.dto.UserBlockDto;
import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 blocking backend: service-layer coverage of block/unblock lifecycle, idempotency,
 * IDOR protection, and blocker-identity non-forgeability. Runs against real MySQL like the rest
 * of this codebase's tests (see application-test.yml) -- no H2, matching the existing convention
 * established in ConnectionServiceTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class BlockServiceTest {

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

    // 1. authenticated user can block another user
    @Test
    void blockUser_createsBlockAttributedToCaller() {
        User a = newUser("block_a");
        User b = newUser("block_b");

        UserBlockDto result = blockService.blockUser(a.getId(), b.getId());

        assertEquals(b.getId(), result.getBlockedUserId());
        assertTrue(userBlockRepository.findByBlockerIdAndBlockedId(a.getId(), b.getId()).isPresent());
    }

    // 3. user cannot block themselves
    @Test
    void blockUser_rejectsSelfBlock() {
        User a = newUser("self_a");

        ApiException ex = assertThrows(ApiException.class, () -> blockService.blockUser(a.getId(), a.getId()));
        assertEquals("CANNOT_BLOCK_SELF", ex.getCode());
    }

    // 4. duplicate block is safely handled -- idempotent, not an error, and never creates a
    // second row.
    @Test
    void blockUser_duplicateBlockIsIdempotent() {
        User a = newUser("dup_a");
        User b = newUser("dup_b");

        UserBlockDto first = blockService.blockUser(a.getId(), b.getId());
        UserBlockDto second = blockService.blockUser(a.getId(), b.getId());

        assertEquals(first.getId(), second.getId());
        assertEquals(1, userBlockRepository.findAllByBlockerIdWithBlocked(a.getId()).size());
    }

    // 5. authenticated user can unblock their own block
    @Test
    void unblockUser_removesOwnBlock() {
        User a = newUser("unblock_a");
        User b = newUser("unblock_b");
        blockService.blockUser(a.getId(), b.getId());

        blockService.unblockUser(a.getId(), b.getId());

        assertTrue(userBlockRepository.findByBlockerIdAndBlockedId(a.getId(), b.getId()).isEmpty());
    }

    // Unblocking a nonexistent block is a safe no-op, not an error.
    @Test
    void unblockUser_nonexistentBlockIsNoOp() {
        User a = newUser("noop_a");
        User b = newUser("noop_b");

        assertDoesNotThrow(() -> blockService.unblockUser(a.getId(), b.getId()));
    }

    // 6. user cannot unblock another user's block -- unblockUser is scoped to blockerId =
    // currentUserId by construction, so a third party's call structurally cannot touch A's block
    // of B, regardless of what target id they pass.
    @Test
    void unblockUser_cannotRemoveAnotherUsersBlock() {
        User a = newUser("idor_a");
        User b = newUser("idor_b");
        User c = newUser("idor_c");
        blockService.blockUser(a.getId(), b.getId());

        blockService.unblockUser(c.getId(), b.getId());

        assertTrue(userBlockRepository.findByBlockerIdAndBlockedId(a.getId(), b.getId()).isPresent(),
                "a's block of b must be untouched by c's unrelated unblock call");
    }

    // 12. forged blocker identity is rejected -- blockUser has no field anywhere to supply a
    // blocker id; it is always the caller-supplied currentUserId, exactly like
    // ConnectionServiceTest's requester-attribution test.
    @Test
    void blockUser_blockerIsAlwaysTheAuthenticatedCaller() {
        User a = newUser("attr_a");
        User b = newUser("attr_b");
        User c = newUser("attr_c");

        UserBlockDto abBlock = blockService.blockUser(a.getId(), b.getId());
        assertEquals(b.getId(), abBlock.getBlockedUserId());
        assertTrue(userBlockRepository.findByBlockerIdAndBlockedId(a.getId(), b.getId()).isPresent());
        assertTrue(userBlockRepository.findByBlockerIdAndBlockedId(c.getId(), b.getId()).isEmpty(),
                "c never blocked b -- only a's call could have created a's block row");
    }

    // 13. block list only exposes current user's blocks
    @Test
    void getMyBlocks_onlyReturnsCallersOwnBlocks() {
        User a = newUser("list_a");
        User b = newUser("list_b");
        User c = newUser("list_c");
        User d = newUser("list_d");
        blockService.blockUser(a.getId(), b.getId());
        blockService.blockUser(c.getId(), d.getId());

        List<UserBlockDto> aBlocks = blockService.getMyBlocks(a.getId());

        assertEquals(1, aBlocks.size());
        assertEquals(b.getId(), aBlocks.get(0).getBlockedUserId());
        assertTrue(aBlocks.stream().noneMatch(bl -> bl.getBlockedUserId().equals(d.getId())),
                "a must never see c's blocks");
    }

    // blockUser rejects a nonexistent target the same way every other endpoint in this codebase
    // rejects a nonexistent user id.
    @Test
    void blockUser_rejectsNonexistentTargetUser() {
        User a = newUser("missing_a");

        ApiException ex = assertThrows(ApiException.class, () -> blockService.blockUser(a.getId(), -999L));
        assertEquals("USER_NOT_FOUND", ex.getCode());
    }
}
