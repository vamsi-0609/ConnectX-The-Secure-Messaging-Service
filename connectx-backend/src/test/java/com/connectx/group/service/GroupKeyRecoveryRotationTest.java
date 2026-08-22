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
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7C: {@link GroupKeyService#recoverByRotating} -- the last-resort recovery rotation that
 * replaced ensureGroupKey's old "mint under the CURRENT authoritative version" fallback. Live
 * multi-device testing proved that fallback unsafe: distributing freshly minted key material
 * under a version number OTHER members might already hold real, different key material for
 * silently overwrote their key, not merely "wasted" a version. This method must instead always
 * claim a genuinely NEW version via {@link GroupService#markKeyRotationRequired}, same as every
 * membership-triggered rotation already does.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupKeyRecoveryRotationTest {

    @Autowired
    private GroupKeyService groupKeyService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
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

    // An active member can claim a new version, and it is EXACTLY one higher than the previous
    // authoritative version -- never a jump, never a reuse of the same number.
    @Test
    void activeMember_canRotateForRecovery_claimsExactlyNextVersion() {
        User owner = newUser("rot_a_owner");
        User member = newUser("rot_a_member");
        GroupDto group = newGroup(owner, "ROT-A Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        int newVersion = groupKeyService.recoverByRotating(member.getId(), group.getId());

        assertEquals(2, newVersion, "group started at version 1; recovery rotation must claim exactly version 2");
        assertEquals(2, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }

    // A removed member cannot trigger a recovery rotation.
    @Test
    void removedMember_cannotRotateForRecovery() {
        User owner = newUser("rot_b_owner");
        User member = newUser("rot_b_member");
        GroupDto group = newGroup(owner, "ROT-B Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupKeyService.recoverByRotating(member.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        // The removal's own rotation already bumped it to 2 -- the rejected call must not add a
        // second, unauthorized bump on top of that.
        assertEquals(2, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }

    // A never-member is rejected identically.
    @Test
    void nonMember_cannotRotateForRecovery() {
        User owner = newUser("rot_c_owner");
        User outsider = newUser("rot_c_outsider");
        GroupDto group = newGroup(owner, "ROT-C Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupKeyService.recoverByRotating(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertEquals(1, chatGroupRepository.findById(group.getId()).orElseThrow().getKeyVersion());
    }

    // A DIRECT conversation id cannot be used with this endpoint either.
    @Test
    void directConversation_cannotRotateForRecovery() {
        User a = newUser("rot_d_a");
        User b = newUser("rot_d_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class,
                () -> groupKeyService.recoverByRotating(a.getId(), direct.getId()));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());
    }

    // Repeated recovery rotations each claim a strictly increasing version -- never collide, never
    // reuse a number, matching the same guarantee membership-triggered rotations already give.
    @Test
    void repeatedRecoveryRotations_eachClaimStrictlyIncreasingVersions() {
        User owner = newUser("rot_e_owner");
        GroupDto group = newGroup(owner, "ROT-E Group");

        int v1 = groupKeyService.recoverByRotating(owner.getId(), group.getId());
        int v2 = groupKeyService.recoverByRotating(owner.getId(), group.getId());
        int v3 = groupKeyService.recoverByRotating(owner.getId(), group.getId());

        assertEquals(2, v1);
        assertEquals(3, v2);
        assertEquals(4, v3);
    }

    // A rotation triggered by a real membership operation (e.g. a removal, which already calls
    // markKeyRotationRequired) and one triggered by recoverByRotating compose safely -- the
    // version keeps advancing monotonically regardless of which mechanism bumped it.
    @Test
    void recoveryRotation_composesWithMembershipTriggeredRotation() {
        User owner = newUser("rot_f_owner");
        User member = newUser("rot_f_member");
        GroupDto group = newGroup(owner, "ROT-F Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        int afterRecovery = groupKeyService.recoverByRotating(member.getId(), group.getId());
        assertEquals(2, afterRecovery);

        groupService.removeMember(owner.getId(), group.getId(), member.getId());
        ChatGroup afterRemoval = chatGroupRepository.findById(group.getId()).orElseThrow();
        assertEquals(3, afterRemoval.getKeyVersion(), "removal's own rotation must advance past the recovery rotation, never collide with it");
    }
}
