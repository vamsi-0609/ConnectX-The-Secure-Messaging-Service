package com.connectx.group.service;

import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupMemberKey;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupMemberKeyRepository;
import com.connectx.message.entity.Message;
import com.connectx.message.repository.MessageRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 6B: database/model foundation for Group E2EE (one shared AES-256 group key per
 * group, wrapped per member, versioned for rotation -- see
 * docs/CONNECTX_GROUP_ARCHITECTURE.md §21 and V5__group_e2ee_key_model.sql). No key generation,
 * wrapping, or rotation exists yet -- these tests only prove the persistence model itself:
 * {@code ChatGroup.keyVersion}, {@code Message.groupKeyVersion}, and the new
 * {@code GroupMemberKey} entity/table, including its unique-per-member constraint. Same
 * real-MySQL, service-layer conventions as every prior Groups suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupE2eeKeyModelTest {

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
    private MessageRepository messageRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    // 1, 9. Group creation still works, and the new ChatGroup.keyVersion column defaults to 1 for
    // a freshly created group.
    @Test
    void createGroup_keyVersionDefaultsToOne() {
        User owner = newUser("kv_owner");

        GroupDto group = groupService.createGroup(owner.getId(), new CreateGroupRequestDto("Key Version Group", null));

        ChatGroup chatGroup = chatGroupRepository.findById(group.getId()).orElseThrow();
        assertEquals(1, chatGroup.getKeyVersion());
        assertEquals("GROUP", group.getType());
    }

    // 2, 3, 10. Message.groupKeyVersion is nullable, and DIRECT message persistence still works
    // with it left null (the only value a DIRECT message should ever have).
    @Test
    void directMessage_persistsWithNullGroupKeyVersion() {
        User user = newUser("dm_gkv");
        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, user));

        Message saved = messageRepository.save(new Message(conversation, user, null, null, "NONE", "cipher", "nonce"));

        Message reloaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertNull(reloaded.getGroupKeyVersion());
    }

    // 4. A GROUP message can persist a groupKeyVersion value.
    @Test
    void groupMessage_persistsGroupKeyVersion() {
        User owner = newUser("gm_gkv");
        GroupDto group = groupService.createGroup(owner.getId(), new CreateGroupRequestDto("GKV Group", null));
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();

        Message message = new Message(conversation, owner, null, null, "AES-256-GCM", "cipher", "nonce");
        message.setGroupKeyVersion(1);
        Message saved = messageRepository.save(message);

        Message reloaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertEquals(1, reloaded.getGroupKeyVersion());
    }

    // 5. GroupMemberKey can persist conversationId, memberUserId, wrappedKey, wrapNonce, and
    // keyVersion, and updatedAt is stamped automatically.
    @Test
    void groupMemberKey_persistsAllFields() {
        User owner = newUser("gmk_owner");
        GroupDto group = groupService.createGroup(owner.getId(), new CreateGroupRequestDto("GMK Group", null));

        GroupMemberKey saved = groupMemberKeyRepository.save(
            new GroupMemberKey(group.getId(), owner.getId(), "opaque-wrapped-key", "opaque-nonce", 1));

        GroupMemberKey reloaded = groupMemberKeyRepository.findById(saved.getId()).orElseThrow();
        assertEquals(group.getId(), reloaded.getConversationId());
        assertEquals(owner.getId(), reloaded.getMemberUserId());
        assertEquals("opaque-wrapped-key", reloaded.getWrappedKey());
        assertEquals("opaque-nonce", reloaded.getWrapNonce());
        assertEquals(1, reloaded.getKeyVersion());
        assertNotNull(reloaded.getUpdatedAt());
    }

    // 7. Multiple different members can each have their own current-key row for the same group.
    @Test
    void groupMemberKey_multipleMembersOfSameGroup_allPersist() {
        User owner = newUser("gmk_multi_owner");
        User memberB = newUser("gmk_multi_b");
        GroupDto group = groupService.createGroup(owner.getId(), new CreateGroupRequestDto("Multi Member Group", null));

        groupMemberKeyRepository.save(new GroupMemberKey(group.getId(), owner.getId(), "wrapped-a", "nonce-a", 1));
        groupMemberKeyRepository.save(new GroupMemberKey(group.getId(), memberB.getId(), "wrapped-b", "nonce-b", 1));

        List<GroupMemberKey> rows = groupMemberKeyRepository.findByConversationId(group.getId());
        assertEquals(2, rows.size());
    }

    // 6, 8. UNIQUE(conversation_id, member_user_id) is enforced -- a member cannot have two
    // current key rows for the same group.
    @Test
    void groupMemberKey_duplicateConversationAndMember_rejected() {
        User owner = newUser("gmk_dup_owner");
        GroupDto group = groupService.createGroup(owner.getId(), new CreateGroupRequestDto("Dup Key Group", null));

        groupMemberKeyRepository.saveAndFlush(new GroupMemberKey(group.getId(), owner.getId(), "wrapped-1", "nonce-1", 1));

        assertThrows(DataIntegrityViolationException.class, () ->
            groupMemberKeyRepository.saveAndFlush(new GroupMemberKey(group.getId(), owner.getId(), "wrapped-2", "nonce-2", 1)));
    }
}
