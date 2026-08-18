package com.connectx.group.service;

import com.connectx.block.service.BlockService;
import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.controller.GroupKeyController;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.GroupMemberKeyDto;
import com.connectx.group.dto.SubmitGroupMemberKeyRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupMemberKey;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupMemberKeyRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 6C: wrapped-group-key distribution endpoints. GroupKeyService/GroupKeyController
 * never generate, receive, decrypt, unwrap, derive, store, log, or return a plaintext GROUP_KEY --
 * every test here treats wrappedKey/wrapNonce as opaque strings, exactly as the production code
 * does. Same real-MySQL, service-layer conventions as every prior Groups suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupKeyServiceTest {

    @Autowired
    private GroupKeyService groupKeyService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
    @Autowired
    private GroupMemberKeyRepository groupMemberKeyRepository;
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

    private SubmitGroupMemberKeyRequestDto keyDto(Long memberUserId, String wrappedKey, String wrapNonce, int keyVersion) {
        return new SubmitGroupMemberKeyRequestDto(memberUserId, wrappedKey, wrapNonce, keyVersion);
    }

    // ==================== AUTHORIZATION ====================

    // 1. an active member can POST a wrapped key.
    @Test
    void activeMember_canSubmitWrappedKey() {
        User owner = newUser("k1_owner");
        GroupDto group = newGroup(owner, "K1 Group");

        GroupMemberKeyDto result = groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                keyDto(owner.getId(), "wrapped-owner", "nonce-owner", 1));

        assertEquals(group.getId(), result.getGroupId());
        assertEquals(1, result.getKeyVersion());
        assertEquals("wrapped-owner", result.getWrappedKey());
        assertEquals("nonce-owner", result.getWrapNonce());
    }

    // 2. a non-member cannot POST (also covers item 24: a groupId the actor has no relationship to).
    @Test
    void nonMember_cannotSubmit() {
        User owner = newUser("k2_owner");
        User outsider = newUser("k2_outsider");
        GroupDto group = newGroup(owner, "K2 Group");

        ApiException ex = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(outsider.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 3. a removed member cannot POST.
    @Test
    void removedMember_cannotSubmit() {
        User owner = newUser("k3_owner");
        User member = newUser("k3_member");
        GroupDto group = newGroup(owner, "K3 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(member.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 4. an active member can GET their own wrapped key.
    @Test
    void activeMember_canGetOwnWrappedKey() {
        User owner = newUser("k4_owner");
        GroupDto group = newGroup(owner, "K4 Group");
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "wrapped-4", "nonce-4", 1));

        GroupMemberKeyDto result = groupKeyService.getMyWrappedKey(owner.getId(), group.getId());

        assertEquals("wrapped-4", result.getWrappedKey());
        assertEquals("nonce-4", result.getWrapNonce());
        assertEquals(1, result.getKeyVersion());
    }

    // 5. a non-member cannot GET.
    @Test
    void nonMember_cannotGet() {
        User owner = newUser("k5_owner");
        User outsider = newUser("k5_outsider");
        GroupDto group = newGroup(owner, "K5 Group");
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 1));

        ApiException ex = assertThrows(ApiException.class, () -> groupKeyService.getMyWrappedKey(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 6. a removed member cannot GET, even though (in this stage, with no rotation/cleanup
    // implemented yet) their old row is still physically present in group_member_keys -- also
    // covers items 18/19: no historical access is possible once membership authorization fails.
    @Test
    void removedMember_cannotGet() {
        User owner = newUser("k6_owner");
        User member = newUser("k6_member");
        GroupDto group = newGroup(owner, "K6 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-member", "nonce-member", 1));
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class, () -> groupKeyService.getMyWrappedKey(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        // The row itself is untouched (no rotation/cleanup in this stage) -- proves the block is
        // authorization, not data deletion.
        assertTrue(groupMemberKeyRepository.findByConversationIdAndMemberUserId(group.getId(), member.getId()).isPresent());
    }

    // 7. a user cannot retrieve another user's wrapped key -- GET is always scoped to the caller.
    @Test
    void userCannotGetAnotherUsersWrappedKey() {
        User owner = newUser("k7_owner");
        User member = newUser("k7_member");
        GroupDto group = newGroup(owner, "K7 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "wrapped-owner-7", "nonce-owner-7", 1));
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-member-7", "nonce-member-7", 1));

        GroupMemberKeyDto ownerResult = groupKeyService.getMyWrappedKey(owner.getId(), group.getId());
        GroupMemberKeyDto memberResult = groupKeyService.getMyWrappedKey(member.getId(), group.getId());

        assertEquals("wrapped-owner-7", ownerResult.getWrappedKey());
        assertEquals("wrapped-member-7", memberResult.getWrappedKey());
        assertNotEquals(ownerResult.getWrappedKey(), memberResult.getWrappedKey());
    }

    // ==================== TARGET VALIDATION ====================

    // 8, 11. a target who was never a member (including a wholly nonexistent user id) is rejected.
    @Test
    void targetNeverMemberOrNonexistent_rejected() {
        User owner = newUser("k8_owner");
        GroupDto group = newGroup(owner, "K8 Group");

        ApiException ex1 = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(999_999_999L, "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex1.getCode());

        User neverMember = newUser("k8_never_member");
        ApiException ex2 = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(neverMember.getId(), "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex2.getCode());
    }

    // 9. a target who is an active member of a DIFFERENT group is rejected for THIS group.
    @Test
    void targetFromAnotherGroup_rejected() {
        User owner1 = newUser("k9_owner1");
        User owner2 = newUser("k9_owner2");
        User memberOfGroup2 = newUser("k9_member2");
        GroupDto group1 = newGroup(owner1, "K9 Group 1");
        GroupDto group2 = newGroup(owner2, "K9 Group 2");
        addRawMember(group2.getId(), memberOfGroup2, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner1.getId(), group1.getId(), keyDto(memberOfGroup2.getId(), "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 10. an inactive (removed) target is rejected.
    @Test
    void inactiveTarget_rejected() {
        User owner = newUser("k10_owner");
        User member = newUser("k10_member");
        GroupDto group = newGroup(owner, "K10 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "w", "n", 1)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // ==================== VERSION ====================

    // 12, 13, 14. only the group's current server-authoritative key version is accepted; both an
    // older and a newer submitted version are rejected without being silently rewritten.
    @Test
    void keyVersion_onlyExactCurrentVersionAccepted() {
        User owner = newUser("k1214_owner");
        GroupDto group = newGroup(owner, "K1214 Group");
        // Simulate a post-rotation group sitting at version 2 -- no rotation flow exists yet in
        // this stage, so the version is advanced directly via the repository, exactly as other
        // Groups race/edge-case tests set up state that has no producing flow yet.
        ChatGroup chatGroup = chatGroupRepository.findById(group.getId()).orElseThrow();
        chatGroup.setKeyVersion(2);
        chatGroupRepository.save(chatGroup);

        ApiException tooOld = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 1)));
        assertEquals("KEY_VERSION_MISMATCH", tooOld.getCode());

        ApiException tooNew = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 3)));
        assertEquals("KEY_VERSION_MISMATCH", tooNew.getCode());

        GroupMemberKeyDto result = groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), "w", "n", 2));
        assertEquals(2, result.getKeyVersion());
    }

    // ==================== DUPLICATES / REPLACE-VS-INSERT ====================

    // 15, 16, 17. first upload inserts; a second upload for the same member replaces it in place
    // (never a duplicate row); the DB uniqueness constraint from Stage 6B remains the real backstop.
    @Test
    void secondUpload_replacesRatherThanDuplicates() {
        User owner = newUser("k1617_owner");
        User member = newUser("k1617_member");
        GroupDto group = newGroup(owner, "K1617 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-v1", "nonce-v1", 1));
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-v1-again", "nonce-v1-again", 1));

        List<GroupMemberKey> rows = groupMemberKeyRepository.findByConversationId(group.getId()).stream()
                .filter(k -> k.getMemberUserId().equals(member.getId())).toList();
        assertEquals(1, rows.size(), "no duplicate row for the same (conversation, member)");
        assertEquals("wrapped-v1-again", rows.get(0).getWrappedKey());
        assertEquals("nonce-v1-again", rows.get(0).getWrapNonce());
    }

    // ==================== HISTORY (see also items 18/19 covered in removedMember_cannotGet) ====================

    // 20. a newly-joined member, after a (simulated) rotation, can only ever receive the CURRENT
    // version -- there is no way to submit or fetch a historical version for them.
    @Test
    void newlyJoinedMember_getsOnlyCurrentVersion() {
        User owner = newUser("k20_owner");
        GroupDto group = newGroup(owner, "K20 Group");
        ChatGroup chatGroup = chatGroupRepository.findById(group.getId()).orElseThrow();
        chatGroup.setKeyVersion(2);
        chatGroupRepository.save(chatGroup);

        User newMember = newUser("k20_new_member");
        addRawMember(group.getId(), newMember, GroupRole.MEMBER);

        ApiException historical = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(newMember.getId(), "w1", "n1", 1)));
        assertEquals("KEY_VERSION_MISMATCH", historical.getCode());

        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(newMember.getId(), "w2", "n2", 2));
        GroupMemberKeyDto result = groupKeyService.getMyWrappedKey(newMember.getId(), group.getId());
        assertEquals(2, result.getKeyVersion());
    }

    // ==================== SECURITY ====================

    // 25. a DIRECT conversation id cannot be used with the group-key endpoint -- chat_groups has no
    // row for it, so it resolves to the same GROUP_NOT_FOUND every group endpoint uses for a
    // non-group id.
    @Test
    void directConversation_cannotUseGroupKeyEndpoint() {
        User a = newUser("k25_a");
        User b = newUser("k25_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class, () ->
                groupKeyService.submitWrappedKey(a.getId(), direct.getId(), keyDto(a.getId(), "w", "n", 1)));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());

        ApiException getEx = assertThrows(ApiException.class, () -> groupKeyService.getMyWrappedKey(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", getEx.getCode());
    }

    // 26. a block relationship between two ACTIVE members neither blocks nor bypasses this
    // endpoint's authorization -- membership is the only thing this endpoint consults (per this
    // stage's own Part 5), so key exchange between already-authorized members is unaffected by
    // blocking either direction.
    @Test
    void blockedActiveMembers_membershipAloneGovernsKeyExchange() {
        User owner = newUser("k26_owner");
        User member = newUser("k26_member");
        GroupDto group = newGroup(owner, "K26 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        blockService.blockUser(owner.getId(), member.getId());

        GroupMemberKeyDto result = groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "w", "n", 1));
        assertEquals("w", result.getWrappedKey());
    }

    // ==================== DATA / NO-PLAINTEXT-KEY STRUCTURAL CHECKS ====================

    // 27, 28. wrappedKey/wrapNonce persist exactly as submitted -- fully opaque, no transformation.
    @Test
    void wrappedKeyAndNonce_persistExactlyAsSubmitted() {
        User owner = newUser("k2728_owner");
        GroupDto group = newGroup(owner, "K2728 Group");
        String opaqueKey = "b64:AbCdEf==/+09";
        String opaqueNonce = "b64:NoNcEvAl==";

        groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(owner.getId(), opaqueKey, opaqueNonce, 1));

        GroupMemberKey row = groupMemberKeyRepository.findByConversationIdAndMemberUserId(group.getId(), owner.getId()).orElseThrow();
        assertEquals(opaqueKey, row.getWrappedKey());
        assertEquals(opaqueNonce, row.getWrapNonce());
    }

    // 29. no plaintext-GROUP_KEY-shaped field exists anywhere in the entity or either DTO --
    // structural proof, not just a repo grep (see also GroupE2eeKeyModelTest's Stage 6B version).
    @Test
    void noPlaintextGroupKeyField_existsAnywhere() {
        List<String> forbidden = List.of("groupkey", "plaintextkey", "privatekey", "secretkey");
        for (Class<?> type : List.of(GroupMemberKey.class, SubmitGroupMemberKeyRequestDto.class, GroupMemberKeyDto.class)) {
            for (Field f : type.getDeclaredFields()) {
                String lower = f.getName().toLowerCase();
                for (String bad : forbidden) {
                    assertFalse(lower.contains(bad), type.getSimpleName() + "." + f.getName() + " looks like a plaintext key field");
                }
            }
        }
        // The request DTO also carries no actor/role field -- the actor is structurally only ever
        // the authenticated principal (item 21/23).
        for (Field f : SubmitGroupMemberKeyRequestDto.class.getDeclaredFields()) {
            String lower = f.getName().toLowerCase();
            assertFalse(lower.contains("actor"), "request DTO must not carry an actor identity field: " + f.getName());
            assertFalse(lower.contains("role"), "request DTO must not carry a role field: " + f.getName());
        }
    }

    // 30. GroupMemberKey (the entity) is never serialized directly -- every GroupKeyController
    // method's return/parameter types are checked structurally for the entity class.
    @Test
    void groupMemberKeyEntity_neverExposedThroughController() {
        for (Method m : GroupKeyController.class.getDeclaredMethods()) {
            assertFalse(mentionsEntity(m.getGenericReturnType()), m.getName() + " return type mentions the GroupMemberKey entity");
            for (Type t : m.getGenericParameterTypes()) {
                assertFalse(mentionsEntity(t), m.getName() + " parameter type mentions the GroupMemberKey entity");
            }
        }
    }

    private boolean mentionsEntity(Type type) {
        return type.getTypeName().contains(GroupMemberKey.class.getName());
    }

    // ==================== CONCURRENCY ====================

    // 31. concurrent first-time uploads for the same member never produce a duplicate row -- one
    // wins the insert, the other (per GroupKeyService#insertOrFallBackToUpdate) falls back to an
    // update of the same row rather than erroring or duplicating.
    @Test
    void concurrentUploadsForSameMember_neverDuplicateRows() throws Exception {
        User owner = newUser("k31_owner");
        User member = newUser("k31_member");
        GroupDto group = newGroup(owner, "K31 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable upload1 = () -> {
            ready.countDown();
            await(go);
            try {
                result1.set(groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-A", "nonce-A", 1)));
            } catch (Throwable t) {
                result1.set(t);
            }
        };
        Runnable upload2 = () -> {
            ready.countDown();
            await(go);
            try {
                result2.set(groupKeyService.submitWrappedKey(owner.getId(), group.getId(), keyDto(member.getId(), "wrapped-B", "nonce-B", 1)));
            } catch (Throwable t) {
                result2.set(t);
            }
        };

        pool.submit(upload1);
        pool.submit(upload2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertInstanceOf(GroupMemberKeyDto.class, result1.get(), "upload1 must not fail: " + result1.get());
        assertInstanceOf(GroupMemberKeyDto.class, result2.get(), "upload2 must not fail: " + result2.get());

        List<GroupMemberKey> rows = groupMemberKeyRepository.findByConversationId(group.getId()).stream()
                .filter(k -> k.getMemberUserId().equals(member.getId())).toList();
        assertEquals(1, rows.size(), "exactly one row must exist for the member after the race");
        assertTrue(rows.get(0).getWrappedKey().equals("wrapped-A") || rows.get(0).getWrappedKey().equals("wrapped-B"),
                "final row must hold one of the two attempted values");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
