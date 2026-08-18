package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
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
 * Groups Stage 3 test items 39-42. Follows the same CountDownLatch-timed race style already proven
 * across GroupInvitationRaceIntegrationTest/ConnectionRequestRaceIntegrationTest.
 * <p>
 * Per docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md's Stage 3 checkpoint (mirroring
 * CONNECTX_GROUP_ARCHITECTURE.md §10's own concurrency analysis): role changes and removals
 * deliberately use no pessimistic locking -- a role update has no uniqueness constraint to race
 * against (last-write-wins is an accepted outcome), and removal only ever decreases the active
 * count, so it cannot itself cause the 50-cap to be exceeded and a second concurrent removal of the
 * same target simply lands on NOT_GROUP_MEMBER. These tests prove those specific claims empirically
 * rather than just asserting them in comments.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupMembershipRaceIntegrationTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private GroupDto newGroup(User owner, String name) {
        return groupService.createGroup(owner.getId(), new CreateGroupRequestDto(name, null));
    }

    private void addRawMember(Long groupId, User user, GroupRole role) {
        Conversation conversation = conversationRepository.findById(groupId).orElseThrow();
        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        conversationMemberRepository.save(member);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 39. two concurrent role changes for the same target remain consistent: no crash/corruption,
    // and the target ends up with exactly one well-defined role (whichever change wins).
    @Test
    void concurrentRoleChanges_remainConsistent() throws Exception {
        User owner = newUser("race39_owner");
        User target = newUser("race39_target");
        GroupDto group = newGroup(owner, "Race39 Group");
        addRawMember(group.getId(), target, GroupRole.MEMBER);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> promoteResult = new AtomicReference<>();
        AtomicReference<Object> demoteResult = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable promote = () -> {
            ready.countDown();
            await(go);
            try {
                promoteResult.set(groupService.changeRole(owner.getId(), group.getId(), target.getId(), GroupRole.ADMIN));
            } catch (Throwable t) {
                promoteResult.set(t);
            }
        };
        Runnable demote = () -> {
            ready.countDown();
            await(go);
            try {
                demoteResult.set(groupService.changeRole(owner.getId(), group.getId(), target.getId(), GroupRole.MEMBER));
            } catch (Throwable t) {
                demoteResult.set(t);
            }
        };

        pool.submit(promote);
        pool.submit(demote);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // Neither call has any legitimate reason to fail (both are OWNER acting on an active
        // MEMBER, requesting a valid role) -- both must succeed cleanly under last-write-wins.
        assertInstanceOf(ConversationMemberDto.class, promoteResult.get(), "promote must not throw: " + promoteResult.get());
        assertInstanceOf(ConversationMemberDto.class, demoteResult.get(), "demote must not throw: " + demoteResult.get());

        GroupRole finalRole = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).orElseThrow().getRole();
        assertTrue(finalRole == GroupRole.ADMIN || finalRole == GroupRole.MEMBER, "final role must be one of the two attempted values: " + finalRole);

        long rows = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(target.getId())).count();
        assertEquals(1, rows, "no duplicate row can result from a plain field update");
    }

    // 40. two concurrent removals of the same target are idempotent/safe: no crash beyond a clean
    // typed conflict, and the target ends up removed exactly once (one row, one deletedAt).
    @Test
    void concurrentRemoval_isIdempotentAndSafe() throws Exception {
        User owner = newUser("race40_owner");
        User target = newUser("race40_target");
        GroupDto group = newGroup(owner, "Race40 Group");
        addRawMember(group.getId(), target, GroupRole.MEMBER);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable remove1 = () -> {
            ready.countDown();
            await(go);
            try {
                groupService.removeMember(owner.getId(), group.getId(), target.getId());
                result1.set("OK");
            } catch (Throwable t) {
                result1.set(t);
            }
        };
        Runnable remove2 = () -> {
            ready.countDown();
            await(go);
            try {
                groupService.removeMember(owner.getId(), group.getId(), target.getId());
                result2.set("OK");
            } catch (Throwable t) {
                result2.set(t);
            }
        };

        pool.submit(remove1);
        pool.submit(remove2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // Per the architecture doc's own analysis, both calls succeeding (the race window closes
        // before either commits) and one throwing NOT_GROUP_MEMBER (the second sees the first's
        // already-committed removal) are both acceptable, harmless outcomes -- what must never
        // happen is any *other* kind of failure, or a corrupted/duplicated end state.
        for (Object result : List.of(result1.get(), result2.get())) {
            if (result instanceof ApiException apiEx) {
                assertEquals("NOT_GROUP_MEMBER", apiEx.getCode(), "an unexpected failure type: " + apiEx.getMessage());
            } else {
                assertEquals("OK", result);
            }
        }

        long rows = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(target.getId())).count();
        assertEquals(1, rows, "still exactly one ConversationMember row -- no duplication");
        assertNotNull(conversationMemberRepository.findByConversationIdAndUserId(group.getId(), target.getId()).orElseThrow().getDeletedAt());
    }

    // 41, 42. a removal (freeing one slot) racing an invitation acceptance (trying to fill one
    // slot) for a *different* user in the same group must never produce duplicate membership rows
    // and must never push the active count past the cap.
    @Test
    void removalRacingAcceptance_neverDuplicatesMembershipOrExceedsCap() throws Exception {
        User owner = newUser("race4142_owner");
        GroupDto group = newGroup(owner, "Race4142 Group");
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;

        // Create the invitation *before* the group is full -- createInvitation itself requires
        // room (evaluateAddMember's GROUP_FULL check applies to sending an invitation too, not
        // just accepting one), so it must happen while at least one slot is still open.
        User invitee = newUser("race4142_invitee");
        Long invitationId = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(invitee.getId())).getInvitation().getId();

        // Now fill every remaining slot (including the one the pending invitation was counting
        // on) directly via the repository, bypassing addMember's own capacity check, so the group
        // sits at exactly the cap with the invitation still PENDING -- the setup this race
        // actually needs: acceptance has zero room until the concurrent removal frees exactly one.
        User toBeRemoved = null;
        int activeSoFar = (int) conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());
        for (int i = 0; i < cap - activeSoFar; i++) {
            User filler = newUser("race4142_filler_" + i);
            addRawMember(group.getId(), filler, GroupRole.MEMBER);
            if (i == 0) {
                toBeRemoved = filler;
            }
        }
        assertEquals(cap, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));

        User finalToBeRemoved = toBeRemoved;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> removeResult = new AtomicReference<>();
        AtomicReference<Object> acceptResult = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable removeOne = () -> {
            ready.countDown();
            await(go);
            try {
                groupService.removeMember(owner.getId(), group.getId(), finalToBeRemoved.getId());
                removeResult.set("OK");
            } catch (Throwable t) {
                removeResult.set(t);
            }
        };
        Runnable acceptOne = () -> {
            ready.countDown();
            await(go);
            try {
                acceptResult.set(groupInvitationService.acceptInvitation(invitee.getId(), invitationId));
            } catch (Throwable t) {
                acceptResult.set(t);
            }
        };

        pool.submit(removeOne);
        pool.submit(acceptOne);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // Removal has no legitimate reason to fail.
        assertEquals("OK", removeResult.get(), "removal must not fail: " + removeResult.get());
        // Acceptance may either succeed (if it observed the freed slot) or be rejected as full
        // (if it raced ahead of the removal's commit) -- both are safe; only the invariants below
        // are load-bearing.
        if (!(acceptResult.get() instanceof com.connectx.group.dto.GroupInvitationDto)) {
            ApiException ex = assertInstanceOf(ApiException.class, acceptResult.get());
            assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());
        }

        long activeCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());
        assertTrue(activeCount <= cap, "active member count must never exceed the cap: was " + activeCount);

        long inviteeRows = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(invitee.getId())).count();
        assertTrue(inviteeRows <= 1, "the invitee must never end up with more than one membership row");
    }
}
