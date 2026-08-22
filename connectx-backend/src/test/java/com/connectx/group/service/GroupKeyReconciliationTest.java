package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.GroupKeyRequestResultDto;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7B: {@link GroupKeyService#requestRewrap} -- the key-reconciliation request half of the
 * "recover the EXISTING current key instead of rotating" fix. This never mints or touches
 * ChatGroup.keyVersion; it only authorizes the requester and fans out a content-free WS notice.
 * The actual re-wrap-and-submit half is exercised via the pre-existing, unmodified
 * submitWrappedKey (already allows any active member to submit a wrapped copy targeting any other
 * active member) -- see GroupKeyServiceTest for that endpoint's own suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupKeyReconciliationTest {

    @Autowired
    private GroupKeyService groupKeyService;
    @Autowired
    private GroupService groupService;
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

    // A. an active member can request the current key; response carries the server-authoritative
    // version (never a client-chosen one) and reports the broadcast actually fired.
    @Test
    void activeMember_canRequestCurrentKey() {
        User owner = newUser("r_a_owner");
        User member = newUser("r_a_member");
        GroupDto group = newGroup(owner, "RA Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        GroupKeyRequestResultDto result = groupKeyService.requestRewrap(member.getId(), group.getId());

        assertEquals(1, result.getKeyVersion());
        assertTrue(result.isBroadcastSent());
    }

    // B. a removed/inactive member is denied, same as every other group-key operation.
    @Test
    void removedMember_cannotRequestRewrap() {
        User owner = newUser("r_b_owner");
        User member = newUser("r_b_member");
        GroupDto group = newGroup(owner, "RB Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupKeyService.requestRewrap(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // B (never-member variant): a user with no relationship to the group at all is denied
    // identically -- cannot learn anything about the group (not even its key version) this way.
    @Test
    void nonMember_cannotRequestRewrap() {
        User owner = newUser("r_b2_owner");
        User outsider = newUser("r_b2_outsider");
        GroupDto group = newGroup(owner, "RB2 Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupKeyService.requestRewrap(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // C. stale key version detection -- after a simulated rotation, the request reflects the NEW
    // authoritative version, not whatever version the requester last knew about (the requester
    // supplies no version at all; the server derives it).
    @Test
    void staleVersion_reflectsCurrentAuthoritativeVersion() {
        User owner = newUser("r_c_owner");
        GroupDto group = newGroup(owner, "RC Group");
        ChatGroup chatGroup = chatGroupRepository.findById(group.getId()).orElseThrow();
        chatGroup.setKeyVersion(5);
        chatGroupRepository.save(chatGroup);

        GroupKeyRequestResultDto result = groupKeyService.requestRewrap(owner.getId(), group.getId());

        assertEquals(5, result.getKeyVersion());
        // Requesting rewrap must NEVER itself advance the version -- that would make this a
        // rotation, exactly the failure mode this feature replaces.
        assertEquals(5, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }

    // D. current key recovery -- B2 requests, an existing holder (owner) re-wraps and submits for
    // B2 via the pre-existing submitWrappedKey (never rotating), and B2 can now fetch its own row.
    @Test
    void requestThenFulfillment_recoversExistingKeyWithoutRotating() {
        User owner = newUser("r_d_owner");
        User memberB = newUser("r_d_memberB");
        GroupDto group = newGroup(owner, "RD Group");
        addRawMember(group.getId(), memberB, GroupRole.MEMBER);
        // Owner already holds the current key (simulated: owner's own wrapped row exists).
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(owner.getId(), "wrapped-owner", "nonce-owner", 1));
        assertTrue(groupMemberKeyRepository.findByConversationIdAndMemberUserId(group.getId(), memberB.getId()).isEmpty(),
                "B2 has no row yet -- the gap this feature recovers from");

        GroupKeyRequestResultDto request = groupKeyService.requestRewrap(memberB.getId(), group.getId());
        assertEquals(1, request.getKeyVersion());

        // Owner's client (the fulfiller) re-wraps its already-resolved key for memberB and submits
        // it via the existing endpoint -- no new backend code path for the actual key material.
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(memberB.getId(), "wrapped-for-B", "nonce-for-B", 1));

        GroupMemberKeyDto recovered = groupKeyService.getMyWrappedKey(memberB.getId(), group.getId());
        assertEquals("wrapped-for-B", recovered.getWrappedKey());
        assertEquals(1, recovered.getKeyVersion());
        // Still version 1 -- recovered without any rotation.
        assertEquals(1, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }

    // E. a DIRECT conversation id cannot be used with this endpoint either.
    @Test
    void directConversation_cannotRequestRewrap() {
        User a = newUser("r_e_a");
        User b = newUser("r_e_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class, () -> groupKeyService.requestRewrap(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());
    }

    // F. repeated reconciliation is idempotent -- back-to-back requests within the throttle window
    // do not re-broadcast, and even if a fulfiller re-submits multiple times, no duplicate rows are
    // ever created (submitWrappedKey's own upsert already guarantees this; this test proves the
    // reconciliation request path composes safely with it).
    @Test
    void repeatedRequests_areThrottledAndFulfillmentStaysIdempotent() {
        User owner = newUser("r_f_owner");
        User memberB = newUser("r_f_memberB");
        GroupDto group = newGroup(owner, "RF Group");
        addRawMember(group.getId(), memberB, GroupRole.MEMBER);

        GroupKeyRequestResultDto first = groupKeyService.requestRewrap(memberB.getId(), group.getId());
        GroupKeyRequestResultDto second = groupKeyService.requestRewrap(memberB.getId(), group.getId());
        assertTrue(first.isBroadcastSent(), "first request in the window must broadcast");
        assertFalse(second.isBroadcastSent(), "immediate repeat within the throttle window must not re-broadcast");

        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(memberB.getId(), "wrapped-v1", "nonce-v1", 1));
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(memberB.getId(), "wrapped-v1-b", "nonce-v1-b", 1));

        List<GroupMemberKey> rows = groupMemberKeyRepository.findByConversationId(group.getId()).stream()
                .filter(k -> k.getMemberUserId().equals(memberB.getId())).toList();
        assertEquals(1, rows.size(), "repeated fulfillment must never duplicate rows");
        assertEquals("wrapped-v1-b", rows.get(0).getWrappedKey());
    }

    // G. no plaintext-key-shaped field exists on the request/response DTO for this feature either.
    @Test
    void noPlaintextKeyField_onReconciliationDto() {
        List<String> forbidden = List.of("groupkey", "plaintextkey", "privatekey", "secretkey", "wrappedkey", "wrapnonce");
        for (Field f : GroupKeyRequestResultDto.class.getDeclaredFields()) {
            String lower = f.getName().toLowerCase();
            for (String bad : forbidden) {
                assertFalse(lower.contains(bad), "GroupKeyRequestResultDto." + f.getName() + " looks like key material");
            }
        }
    }

    // H. existing group-key rows (created before this feature existed) remain fully compatible --
    // requesting rewrap for a member who ALREADY has a current-version row is harmless (still no
    // rotation, still just a notice).
    @Test
    void existingCurrentRow_requestIsHarmlessNoOp() {
        User owner = newUser("r_h_owner");
        GroupDto group = newGroup(owner, "RH Group");
        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(owner.getId(), "wrapped-existing", "nonce-existing", 1));

        groupKeyService.requestRewrap(owner.getId(), group.getId());

        GroupMemberKeyDto stillThere = groupKeyService.getMyWrappedKey(owner.getId(), group.getId());
        assertEquals("wrapped-existing", stillThere.getWrappedKey());
        assertEquals(1, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }
}
