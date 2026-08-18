package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.SubmitGroupMemberKeyRequestDto;
import com.connectx.group.entity.GroupMemberKey;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.repository.GroupMemberKeyRepository;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.service.MessageService;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups E2EE messaging stage: key-VERSION lifecycle (rotation on join/removal/leave, no rotation
 * on role/ownership changes), ownership transfer, and MessageService's groupKeyVersion enforcement
 * for GROUP TEXT sends. Reuses the exact same real-MySQL, service-layer conventions as every prior
 * Groups suite (GroupKeyServiceTest, GroupMessagingTest, etc).
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupKeyRotationTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupInvitationService groupInvitationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private GroupKeyService groupKeyService;
    @Autowired
    private MessageService messageService;
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

    private int currentVersion(Long groupId) {
        return chatGroupRepository.findById(groupId).orElseThrow().getKeyVersion();
    }

    private SendMessageRequestDto groupTextDto(Long conversationId, String ciphertext, Integer groupKeyVersion) {
        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(conversationId);
        dto.setEncryptionAlgorithm("AES-256-GCM");
        dto.setCiphertext(ciphertext);
        dto.setNonce("nonce-" + System.nanoTime());
        dto.setGroupKeyVersion(groupKeyVersion);
        return dto;
    }

    // ==================== ROTATION TRIGGERS ====================

    @Test
    void freshGroup_startsAtVersion1() {
        User owner = newUser("rot1_owner");
        GroupDto group = newGroup(owner, "Rotation Fresh Group");
        assertEquals(1, group.getKeyVersion());
        assertEquals(1, currentVersion(group.getId()));
    }

    @Test
    void directAddJoin_rotatesKeyVersion() {
        User owner = newUser("rot2_owner");
        User target = newUser("rot2_target");
        GroupDto group = newGroup(owner, "Rotation DirectAdd Group");
        assertEquals(1, currentVersion(group.getId()));

        // Force a mutual connection so evaluateAddMember resolves to DIRECT_ADD.
        connect(owner, target);
        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("DIRECT_ADDED", result.getOutcome());

        assertEquals(2, currentVersion(group.getId()), "a new member joining must rotate the group key");
    }

    @Test
    void invitationAcceptJoin_rotatesKeyVersion() {
        User owner = newUser("rot3_owner");
        User target = newUser("rot3_target");
        GroupDto group = newGroup(owner, "Rotation Accept Group");

        CreateGroupInvitationResponseDto invited = groupInvitationService.createInvitation(
                owner.getId(), group.getId(), new CreateGroupInvitationRequestDto(target.getId()));
        assertEquals("INVITATION_SENT", invited.getOutcome());
        assertEquals(1, currentVersion(group.getId()), "sending an invitation alone must not rotate anything");

        groupInvitationService.acceptInvitation(target.getId(), invited.getInvitation().getId());

        assertEquals(2, currentVersion(group.getId()), "accepting an invitation must rotate the group key");
    }

    @Test
    void removal_rotatesKeyVersion() {
        User owner = newUser("rot4_owner");
        User member = newUser("rot4_member");
        GroupDto group = newGroup(owner, "Rotation Removal Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        assertEquals(1, currentVersion(group.getId()));

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        assertEquals(2, currentVersion(group.getId()), "removing a member must rotate the group key");
    }

    @Test
    void leave_rotatesKeyVersion() {
        User owner = newUser("rot5_owner");
        User member = newUser("rot5_member");
        GroupDto group = newGroup(owner, "Rotation Leave Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.leaveGroup(member.getId(), group.getId());

        assertEquals(2, currentVersion(group.getId()), "a member leaving must rotate the group key");
    }

    @Test
    void roleChange_neverRotatesKeyVersion() {
        User owner = newUser("rot6_owner");
        User member = newUser("rot6_member");
        GroupDto group = newGroup(owner, "Rotation RoleChange Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.changeRole(owner.getId(), group.getId(), member.getId(), GroupRole.ADMIN);
        groupService.changeRole(owner.getId(), group.getId(), member.getId(), GroupRole.MEMBER);

        assertEquals(1, currentVersion(group.getId()), "promote/demote must never rotate the group key");
    }

    @Test
    void settingsChange_neverRotatesKeyVersion() {
        User owner = newUser("rot7_owner");
        GroupDto group = newGroup(owner, "Rotation Settings Group");

        com.connectx.group.dto.UpdateGroupSettingsRequestDto settingsDto = new com.connectx.group.dto.UpdateGroupSettingsRequestDto();
        settingsDto.setWhoCanInvite("ALL_MEMBERS");
        settingsDto.setWhoCanSendMessages("ADMINS_ONLY");
        settingsDto.setWhoCanEditGroupInfo("ALL_MEMBERS");
        groupService.updateSettings(owner.getId(), group.getId(), settingsDto);

        assertEquals(1, currentVersion(group.getId()), "a settings change must never rotate the group key");
    }

    // ==================== OWNERSHIP TRANSFER ====================

    @Test
    void ownershipTransfer_swapsRoles_neverRotatesKey() {
        User owner = newUser("own1_owner");
        User member = newUser("own1_member");
        GroupDto group = newGroup(owner, "Ownership Transfer Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.transferOwnership(owner.getId(), group.getId(), member.getId());

        GroupRole ownerNowRole = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), owner.getId())
                .orElseThrow().getRole();
        GroupRole memberNowRole = conversationMemberRepository.findByConversationIdAndUserId(group.getId(), member.getId())
                .orElseThrow().getRole();
        assertEquals(GroupRole.ADMIN, ownerNowRole, "previous owner becomes ADMIN");
        assertEquals(GroupRole.OWNER, memberNowRole, "target becomes OWNER");
        assertEquals(1, currentVersion(group.getId()), "ownership transfer must never rotate the group key");
    }

    @Test
    void ownershipTransfer_onlyCurrentOwnerMayCall() {
        User owner = newUser("own2_owner");
        User admin = newUser("own2_admin");
        User member = newUser("own2_member");
        GroupDto group = newGroup(owner, "Ownership Forbidden Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.transferOwnership(admin.getId(), group.getId(), member.getId()));
        assertEquals("OWNER_ONLY", ex.getCode());
    }

    @Test
    void ownershipTransfer_targetMustBeActiveMember() {
        User owner = newUser("own3_owner");
        User outsider = newUser("own3_outsider");
        GroupDto group = newGroup(owner, "Ownership NonMember Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.transferOwnership(owner.getId(), group.getId(), outsider.getId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    @Test
    void ownershipTransfer_cannotTransferToSelf() {
        User owner = newUser("own4_owner");
        GroupDto group = newGroup(owner, "Ownership Self Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.transferOwnership(owner.getId(), group.getId(), owner.getId()));
        assertEquals("CANNOT_TRANSFER_TO_SELF", ex.getCode());
    }

    @Test
    void ownershipTransfer_previousOwnerCanThenLeave() {
        User owner = newUser("own5_owner");
        User member = newUser("own5_member");
        GroupDto group = newGroup(owner, "Ownership Then Leave Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupService.transferOwnership(owner.getId(), group.getId(), member.getId());

        // The former owner is now ADMIN and can leave normally (OWNER alone was blocked).
        assertDoesNotThrow(() -> groupService.leaveGroup(owner.getId(), group.getId()));
    }

    // ==================== WRAPPER IDENTITY ====================

    @Test
    void submittedKey_recordsWhoWrappedIt() {
        User owner = newUser("wrap1_owner");
        User member = newUser("wrap1_member");
        GroupDto group = newGroup(owner, "Wrapper Identity Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        groupKeyService.submitWrappedKey(owner.getId(), group.getId(),
                new SubmitGroupMemberKeyRequestDto(member.getId(), "wrapped", "nonce", 1));

        GroupMemberKey row = groupMemberKeyRepository.findByConversationIdAndMemberUserId(group.getId(), member.getId())
                .orElseThrow();
        assertEquals(owner.getId(), row.getWrappedByUserId(), "wrappedByUserId must be the actual submitting actor, never client-supplied");
    }

    // ==================== MESSAGE groupKeyVersion ENFORCEMENT ====================

    @Test
    void groupTextSend_requiresMatchingKeyVersion() {
        User owner = newUser("msgv1_owner");
        GroupDto group = newGroup(owner, "MsgVersion Group");

        ApiException missing = assertThrows(ApiException.class,
                () -> messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "ct", null)));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", missing.getCode());

        ApiException stale = assertThrows(ApiException.class,
                () -> messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "ct", 0)));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", stale.getCode());

        ApiException future = assertThrows(ApiException.class,
                () -> messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "ct", 2)));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", future.getCode());

        MessageDto sent = messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "ct", 1));
        assertEquals(1, sent.getGroupKeyVersion());
    }

    @Test
    void groupTextSend_staleVersionAfterRotation_isRejected() {
        User owner = newUser("msgv2_owner");
        User member = newUser("msgv2_member");
        GroupDto group = newGroup(owner, "MsgVersion Rotated Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        // Owner sends fine at version 1.
        messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "before", 1));

        // Removal rotates the key to version 2.
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        // A client still holding the old (version 1) key can no longer send.
        ApiException ex = assertThrows(ApiException.class,
                () -> messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "stale", 1)));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", ex.getCode());

        // The current version still works.
        MessageDto sent = messageService.sendMessage(owner.getId(), groupTextDto(group.getId(), "current", 2));
        assertEquals(2, sent.getGroupKeyVersion());
    }

    @Test
    void directMessage_neverRequiresGroupKeyVersion() {
        User a = newUser("msgv3_a");
        User b = newUser("msgv3_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(com.connectx.conversation.entity.ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(direct.getId());
        dto.setEncryptionAlgorithm("ECDH-P256+AES-256-GCM");
        dto.setCiphertext("ct");
        dto.setNonce("n");
        // groupKeyVersion deliberately left null -- must not matter for DIRECT.
        assertDoesNotThrow(() -> messageService.sendMessage(a.getId(), dto));
    }

    private void connect(User a, User b) {
        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), connectionService.getPendingIncomingRequests(b.getId()).get(0).getId());
    }
}
