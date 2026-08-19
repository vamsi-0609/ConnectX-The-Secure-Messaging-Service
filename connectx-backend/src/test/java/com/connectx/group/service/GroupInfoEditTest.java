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
import com.connectx.group.dto.UpdateGroupInfoRequestDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group "profile" editing (name/description) -- GroupService#updateGroupInfo, gated by
 * who_can_edit_group_info (GroupAuthorizationService#requireCanEditGroupInfo), deliberately NOT
 * the owner-only rule GroupSettingsAndPrivacyTest's suite covers for the three policy ENUMs. Same
 * real-MySQL, service-layer conventions as every other Groups suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupInfoEditTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
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

    private UpdateGroupInfoRequestDto infoDto(String name, String description) {
        UpdateGroupInfoRequestDto dto = new UpdateGroupInfoRequestDto();
        dto.setName(name);
        dto.setDescription(description);
        return dto;
    }

    // 1. OWNER can edit both name and description.
    @Test
    void owner_canEditNameAndDescription() {
        User owner = newUser("info1_owner");
        GroupDto group = newGroup(owner, "Info1 Group");

        GroupDto updated = groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto("Info1 Renamed", "New description"));

        assertEquals("Info1 Renamed", updated.getName());
        assertEquals("New description", updated.getDescription());
    }

    // 2. ADMIN can edit under the default OWNER_ADMIN_ONLY policy.
    @Test
    void admin_canEditUnderDefaultPolicy() {
        User owner = newUser("info2_owner");
        User admin = newUser("info2_admin");
        GroupDto group = newGroup(owner, "Info2 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        GroupDto updated = groupService.updateGroupInfo(admin.getId(), group.getId(), infoDto("Info2 Renamed", null));
        assertEquals("Info2 Renamed", updated.getName());
    }

    // 3. MEMBER cannot edit under the default OWNER_ADMIN_ONLY policy.
    @Test
    void member_cannotEditUnderDefaultPolicy() {
        User owner = newUser("info3_owner");
        User member = newUser("info3_member");
        GroupDto group = newGroup(owner, "Info3 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(member.getId(), group.getId(), infoDto("Hijacked", null)));
        assertEquals("EDIT_NOT_PERMITTED", ex.getCode());
        assertEquals("Info3 Group", chatGroupRepository.findById(group.getId()).orElseThrow().getName());
    }

    // 4. MEMBER can edit once the group's policy is switched to ALL_MEMBERS.
    @Test
    void member_canEditUnderAllMembersPolicy() {
        User owner = newUser("info4_owner");
        User member = newUser("info4_member");
        GroupDto group = newGroup(owner, "Info4 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        UpdateGroupSettingsRequestDto settings = new UpdateGroupSettingsRequestDto();
        settings.setWhoCanEditGroupInfo("ALL_MEMBERS");
        groupService.updateSettings(owner.getId(), group.getId(), settings);

        GroupDto updated = groupService.updateGroupInfo(member.getId(), group.getId(), infoDto("Info4 Renamed by member", null));
        assertEquals("Info4 Renamed by member", updated.getName());
    }

    // 5. a forged/never-issued actor id is rejected.
    @Test
    void forgedActorId_rejected() {
        User owner = newUser("info5_owner");
        GroupDto group = newGroup(owner, "Info5 Group");
        long neverIssuedUserId = 987_654_324L;

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(neverIssuedUserId, group.getId(), infoDto("Hijacked", null)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 6. the owner of one group cannot edit a different group's info (cross-group edit).
    @Test
    void crossGroupEdit_rejected() {
        User ownerA = newUser("info6_owner_a");
        User ownerB = newUser("info6_owner_b");
        newGroup(ownerA, "Info6 Group A");
        GroupDto groupB = newGroup(ownerB, "Info6 Group B");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(ownerA.getId(), groupB.getId(), infoDto("Hijacked", null)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
        assertEquals("Info6 Group B", chatGroupRepository.findById(groupB.getId()).orElseThrow().getName());
    }

    // 7. a DIRECT conversation id is rejected -- there is no ChatGroup row for a DIRECT
    // conversation, so this must fail exactly like an unknown group id rather than editing
    // something unrelated.
    @Test
    void directConversation_rejected() {
        User a = newUser("info7_a");
        User b = newUser("info7_b");
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(a.getId(), direct.getId(), infoDto("Hijacked", null)));
        assertEquals("GROUP_NOT_FOUND", ex.getCode());
    }

    // 8. a deleted group is rejected -- the owner's own membership row is soft-deleted by
    // deleteGroup, so even the former owner can no longer edit it.
    @Test
    void deletedGroup_rejected() {
        User owner = newUser("info8_owner");
        GroupDto group = newGroup(owner, "Info8 Group");
        groupService.deleteGroup(owner.getId(), group.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto("Hijacked", null)));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // 9. a blank name is rejected rather than silently ignored or emptied.
    @Test
    void blankName_rejected() {
        User owner = newUser("info9_owner");
        GroupDto group = newGroup(owner, "Info9 Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto("   ", null)));
        assertEquals("GROUP_NAME_REQUIRED", ex.getCode());
        assertEquals("Info9 Group", chatGroupRepository.findById(group.getId()).orElseThrow().getName());
    }

    // 10. an explicit blank description clears an existing one, mirroring createGroup's own
    // validateDescription convention.
    @Test
    void blankDescription_clearsExistingOne() {
        User owner = newUser("info10_owner");
        GroupDto group = newGroup(owner, "Info10 Group");
        groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto(null, "Has a description"));
        assertEquals("Has a description", chatGroupRepository.findById(group.getId()).orElseThrow().getDescription());

        GroupDto updated = groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto(null, "   "));
        assertNull(updated.getDescription());
    }

    // 11. omitting a field (null) leaves it untouched -- editing only the name must not clear an
    // existing description, and vice versa.
    @Test
    void omittedField_leftUntouched() {
        User owner = newUser("info11_owner");
        GroupDto group = newGroup(owner, "Info11 Group");
        groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto(null, "Original description"));

        GroupDto updated = groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto("Info11 Renamed", null));
        assertEquals("Info11 Renamed", updated.getName());
        assertEquals("Original description", updated.getDescription(), "description must survive a name-only update");
    }

    // 12. a name over 100 characters is rejected, matching createGroup's own limit.
    @Test
    void nameTooLong_rejected() {
        User owner = newUser("info12_owner");
        GroupDto group = newGroup(owner, "Info12 Group");
        String tooLong = "x".repeat(101);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.updateGroupInfo(owner.getId(), group.getId(), infoDto(tooLong, null)));
        assertEquals("GROUP_NAME_TOO_LONG", ex.getCode());
    }
}
