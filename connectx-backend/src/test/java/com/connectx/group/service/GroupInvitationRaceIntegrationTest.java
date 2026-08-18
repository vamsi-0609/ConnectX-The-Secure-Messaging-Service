package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.GroupInvitationDto;
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
 * Groups Stage 2 test items 32-35: the concurrency invariants GroupService#addMember's
 * pessimistic-lock design and group_invitations' pending_invite_key constraint exist to guarantee.
 * Follows the same CountDownLatch-timed race style already proven in
 * ConnectionRequestRaceIntegrationTest/DirectConversationRaceIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupInvitationRaceIntegrationTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataSource dataSource;

    // Mirrors ConnectionRequestRaceIntegrationTest's identical need: group_invitations'
    // pending_invite_key generated column + partial-unique index is a MySQL trick not expressible
    // via plain JPA annotations (see GroupInvitation's class javadoc) -- connectx_db has it from
    // the Stage 0B migration, connectx_test_db (rebuilt from entity metadata on every run) does
    // not, so this adds the identical DDL directly.
    @BeforeEach
    void ensureGroupInvitationPartialUniqueIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            boolean columnExists;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'group_invitations' AND COLUMN_NAME = 'pending_invite_key'")) {
                rs.next();
                columnExists = rs.getInt(1) > 0;
            }
            if (!columnExists) {
                stmt.execute(
                        "ALTER TABLE group_invitations ADD COLUMN pending_invite_key VARCHAR(41) GENERATED ALWAYS AS (" +
                        "CASE WHEN status = 'PENDING' THEN CONCAT(group_id, '_', invitee_user_id) ELSE NULL END) VIRTUAL, " +
                        "ADD UNIQUE KEY uk_groupinv_pending_pair (pending_invite_key)");
            }
        }
    }

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private void connect(User a, User b) {
        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), connectionService.getPendingIncomingRequests(b.getId()).get(0).getId());
    }

    private GroupDto newGroup(User owner, String name) {
        return groupService.createGroup(owner.getId(), new CreateGroupRequestDto(name, null));
    }

    private void fillGroupTo(Long groupId, int activeCountIncludingOwner) {
        // Owner already occupies slot 1.
        for (int i = 1; i < activeCountIncludingOwner; i++) {
            User filler = newUser("race_filler_" + groupId + "_" + i);
            addRawMember(groupId, filler);
        }
    }

    private void addRawMember(Long groupId, User user) {
        var conversation = groupServiceConversation(groupId);
        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(GroupRole.MEMBER);
        conversationMemberRepository.save(member);
    }

    private com.connectx.conversation.entity.Conversation groupServiceConversation(Long groupId) {
        // Small local helper so this file doesn't need its own ConversationRepository field just
        // for building a reference to pass into ConversationMember's constructor.
        return conversationMemberRepository.findByConversationId(groupId).stream().findFirst()
                .map(ConversationMember::getConversation)
                .orElseThrow(() -> new IllegalStateException("group must already have at least the owner as a member"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 32. two concurrent accepts of the SAME invitation must never create duplicate membership.
    @Test
    void concurrentAcceptsOfSameInvitation_neverCreateDuplicateMembership() throws Exception {
        User owner = newUser("race32_owner");
        User target = newUser("race32_target");
        GroupDto group = newGroup(owner, "Race32 Group");
        CreateGroupInvitationResponseDto created = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        Long invitationId = created.getInvitation().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable accept1 = () -> {
            ready.countDown();
            await(go);
            try {
                result1.set(groupInvitationService.acceptInvitation(target.getId(), invitationId));
            } catch (Throwable t) {
                result1.set(t);
            }
        };
        Runnable accept2 = () -> {
            ready.countDown();
            await(go);
            try {
                result2.set(groupInvitationService.acceptInvitation(target.getId(), invitationId));
            } catch (Throwable t) {
                result2.set(t);
            }
        };

        pool.submit(accept1);
        pool.submit(accept2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        int successes = 0;
        for (Object result : List.of(result1.get(), result2.get())) {
            if (result instanceof GroupInvitationDto) {
                successes++;
            } else if (result instanceof ApiException apiEx) {
                assertTrue(apiEx.getCode().equals("INVITATION_NOT_PENDING") || apiEx.getCode().equals("ALREADY_GROUP_MEMBER"),
                        "the losing side must fail with a clean typed conflict, not something else: " + apiEx.getMessage());
            } else {
                fail("unexpected outcome type: " + result);
            }
        }
        assertEquals(1, successes, "exactly one of the two concurrent accepts must win");

        long memberRows = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(target.getId())).count();
        assertEquals(1, memberRows, "exactly one ConversationMember row must exist for the target after the race");
    }

    // 33. two concurrent accepts of DIFFERENT invitations for the SAME group must never exceed the cap.
    @Test
    void concurrentAcceptsOfDifferentInvitations_neverExceedActiveMemberCap() throws Exception {
        User owner = newUser("race33_owner");
        GroupDto group = newGroup(owner, "Race33 Group");
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;
        fillGroupTo(group.getId(), cap - 1); // owner + (cap - 2) fillers = cap - 1 active, one slot left

        User targetA = newUser("race33_target_a");
        User targetB = newUser("race33_target_b");
        Long invitationA = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(targetA.getId())).getInvitation().getId();
        Long invitationB = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(targetB.getId())).getInvitation().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> resultA = new AtomicReference<>();
        AtomicReference<Object> resultB = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable acceptA = () -> {
            ready.countDown();
            await(go);
            try {
                resultA.set(groupInvitationService.acceptInvitation(targetA.getId(), invitationA));
            } catch (Throwable t) {
                resultA.set(t);
            }
        };
        Runnable acceptB = () -> {
            ready.countDown();
            await(go);
            try {
                resultB.set(groupInvitationService.acceptInvitation(targetB.getId(), invitationB));
            } catch (Throwable t) {
                resultB.set(t);
            }
        };

        pool.submit(acceptA);
        pool.submit(acceptB);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        int successes = 0;
        for (Object result : List.of(resultA.get(), resultB.get())) {
            if (result instanceof GroupInvitationDto) {
                successes++;
            } else if (result instanceof ApiException apiEx) {
                assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", apiEx.getCode());
            } else {
                fail("unexpected outcome type: " + result);
            }
        }
        assertEquals(1, successes, "only one of the two accepts can fit in the single remaining slot");

        long activeCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());
        assertEquals(cap, activeCount, "active member count must land exactly on the cap, never exceed it");
    }

    // 34. a concurrent direct-add and a concurrent invitation-accept for the SAME group must never
    // together exceed the cap.
    @Test
    void concurrentDirectAddAndInvitationAccept_neverExceedActiveMemberCap() throws Exception {
        User owner = newUser("race34_owner");
        GroupDto group = newGroup(owner, "Race34 Group");
        int cap = GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS;
        fillGroupTo(group.getId(), cap - 1); // one slot left

        User directAddTarget = newUser("race34_direct_target");
        connect(owner, directAddTarget); // connected + default ANYONE privacy -> DIRECT_ADD path
        User inviteTarget = newUser("race34_invite_target");
        Long invitationId = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(inviteTarget.getId())).getInvitation().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> directAddResult = new AtomicReference<>();
        AtomicReference<Object> acceptResult = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable directAdd = () -> {
            ready.countDown();
            await(go);
            try {
                directAddResult.set(groupInvitationService.createInvitation(
                        owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(directAddTarget.getId())));
            } catch (Throwable t) {
                directAddResult.set(t);
            }
        };
        Runnable accept = () -> {
            ready.countDown();
            await(go);
            try {
                acceptResult.set(groupInvitationService.acceptInvitation(inviteTarget.getId(), invitationId));
            } catch (Throwable t) {
                acceptResult.set(t);
            }
        };

        pool.submit(directAdd);
        pool.submit(accept);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        boolean directAddSucceeded = directAddResult.get() instanceof CreateGroupInvitationResponseDto r && "DIRECT_ADDED".equals(r.getOutcome());
        boolean acceptSucceeded = acceptResult.get() instanceof GroupInvitationDto;
        int successes = (directAddSucceeded ? 1 : 0) + (acceptSucceeded ? 1 : 0);

        if (!directAddSucceeded) {
            ApiException ex = assertInstanceOf(ApiException.class, directAddResult.get());
            assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());
        }
        if (!acceptSucceeded) {
            ApiException ex = assertInstanceOf(ApiException.class, acceptResult.get());
            assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());
        }
        assertEquals(1, successes, "only one of direct-add / accept can fit in the single remaining slot");

        long activeCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId());
        assertEquals(cap, activeCount, "active member count must land exactly on the cap, never exceed it");
    }

    // 35. two concurrent invitation-creation requests for the SAME (group, target) pair must never
    // both succeed as PENDING invitations.
    @Test
    void concurrentDuplicateInvitationCreation_resolvesToExactlyOnePendingRow() throws Exception {
        User owner = newUser("race35_owner");
        User target = newUser("race35_target");
        GroupDto group = newGroup(owner, "Race35 Group");
        // Not connected, so both attempts resolve to INVITATION_REQUIRED (not DIRECT_ADD),
        // forcing the actual invitation-insert race this test targets.

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable invite1 = () -> {
            ready.countDown();
            await(go);
            try {
                result1.set(groupInvitationService.createInvitation(owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
            } catch (Throwable t) {
                result1.set(t);
            }
        };
        Runnable invite2 = () -> {
            ready.countDown();
            await(go);
            try {
                result2.set(groupInvitationService.createInvitation(owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId())));
            } catch (Throwable t) {
                result2.set(t);
            }
        };

        pool.submit(invite1);
        pool.submit(invite2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        int successes = 0;
        for (Object result : List.of(result1.get(), result2.get())) {
            if (result instanceof CreateGroupInvitationResponseDto r && "INVITATION_SENT".equals(r.getOutcome())) {
                successes++;
            } else if (result instanceof ApiException apiEx) {
                assertEquals("INVITATION_ALREADY_PENDING", apiEx.getCode(),
                        "the losing side must fail with the typed conflict error, not something else: " + apiEx.getMessage());
            } else {
                fail("unexpected outcome type: " + result);
            }
        }
        assertEquals(1, successes, "exactly one of the two concurrent invitation-creation attempts must win");
    }
}
