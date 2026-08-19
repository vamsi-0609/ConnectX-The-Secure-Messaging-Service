package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group photo upload/removal (the KNOWN BUG fix: CreateGroupModal's camera button previously did
 * nothing). Mirrors GroupSettingsAndPrivacyTest's real-MySQL, service-layer conventions. Covers the
 * one new authorization rule this stage adds (requireCanEditGroupInfo, the first actual enforcement
 * of who_can_edit_group_info) and LocalGroupImageStorage's validation, exactly the way
 * LocalProfileImageStorage's identical rules would be tested if they had their own suite.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupPhotoTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private UserRepository userRepository;

    // A real, minimal, decodable 1x1 red-pixel PNG -- LocalGroupImageStorage#validateImageContent
    // actually decodes it via ImageIO, so a fake/garbage byte string would incorrectly fail every
    // "valid photo" test the same way a real corrupt upload should.
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

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

    private MultipartFile pngFile() {
        return new MockMultipartFile("file", "avatar.png", "image/png", TINY_PNG);
    }

    @Test
    void owner_canUploadAvatar_andUrlIsGroupImagesNamespace_neverProfileImages() {
        User owner = newUser("photo1_owner");
        GroupDto group = newGroup(owner, "Photo1 Group");

        GroupDto updated = groupService.uploadAvatar(owner.getId(), group.getId(), pngFile());

        assertNotNull(updated.getAvatarUrl());
        assertTrue(updated.getAvatarUrl().startsWith("/api/v1/group-images/" + group.getId()),
                "must resolve through the group-image endpoint, never /api/v1/profile-images");
        assertFalse(updated.getAvatarUrl().contains("profile-images"));
    }

    @Test
    void owner_canRemoveAvatar() {
        User owner = newUser("photo2_owner");
        GroupDto group = newGroup(owner, "Photo2 Group");
        groupService.uploadAvatar(owner.getId(), group.getId(), pngFile());

        GroupDto updated = groupService.removeAvatar(owner.getId(), group.getId());

        assertNull(updated.getAvatarUrl());
    }

    @Test
    void admin_canUploadAvatar_defaultOwnerAdminOnlySetting() {
        User owner = newUser("photo3_owner");
        User admin = newUser("photo3_admin");
        GroupDto group = newGroup(owner, "Photo3 Group");
        addRawMember(group.getId(), admin, GroupRole.ADMIN);

        GroupDto updated = groupService.uploadAvatar(admin.getId(), group.getId(), pngFile());
        assertNotNull(updated.getAvatarUrl());
    }

    @Test
    void member_cannotUploadAvatar_defaultOwnerAdminOnlySetting() {
        User owner = newUser("photo4_owner");
        User member = newUser("photo4_member");
        GroupDto group = newGroup(owner, "Photo4 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(member.getId(), group.getId(), pngFile()));
        assertEquals("EDIT_NOT_PERMITTED", ex.getCode());
    }

    @Test
    void member_canUploadAvatar_whenWhoCanEditGroupInfoIsAllMembers() {
        User owner = newUser("photo5_owner");
        User member = newUser("photo5_member");
        GroupDto group = newGroup(owner, "Photo5 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        UpdateGroupSettingsRequestDto settings = new UpdateGroupSettingsRequestDto();
        settings.setWhoCanEditGroupInfo("ALL_MEMBERS");
        groupService.updateSettings(owner.getId(), group.getId(), settings);

        GroupDto updated = groupService.uploadAvatar(member.getId(), group.getId(), pngFile());
        assertNotNull(updated.getAvatarUrl());
    }

    @Test
    void nonMember_cannotUploadAvatar() {
        User owner = newUser("photo6_owner");
        User outsider = newUser("photo6_outsider");
        GroupDto group = newGroup(owner, "Photo6 Group");

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(outsider.getId(), group.getId(), pngFile()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    @Test
    void invalidImageType_rejectedGracefully() {
        User owner = newUser("photo7_owner");
        GroupDto group = newGroup(owner, "Photo7 Group");
        MultipartFile notAnImage = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(owner.getId(), group.getId(), notAnImage));
        assertEquals("INVALID_IMAGE_TYPE", ex.getCode());
    }

    @Test
    void oversizedImage_rejectedGracefully() {
        User owner = newUser("photo8_owner");
        GroupDto group = newGroup(owner, "Photo8 Group");
        byte[] oversized = new byte[16 * 1024 * 1024];
        MultipartFile huge = new MockMultipartFile("file", "huge.png", "image/png", oversized);

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(owner.getId(), group.getId(), huge));
        assertEquals("IMAGE_TOO_LARGE", ex.getCode());
    }

    @Test
    void corruptImageContent_rejectedGracefully() {
        User owner = newUser("photo9_owner");
        GroupDto group = newGroup(owner, "Photo9 Group");
        // Correct declared content-type, but the bytes are not actually a decodable image --
        // LocalGroupImageStorage#validateImageContent must catch this via ImageIO, not just trust
        // the client-declared MIME type.
        MultipartFile fakePng = new MockMultipartFile("file", "fake.png", "image/png", "not a real png".getBytes());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(owner.getId(), group.getId(), fakePng));
        assertEquals("INVALID_IMAGE", ex.getCode());
    }

    @Test
    void removedMember_cannotUploadAvatar() {
        User owner = newUser("photo10_owner");
        User member = newUser("photo10_member");
        GroupDto group = newGroup(owner, "Photo10 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.uploadAvatar(member.getId(), group.getId(), pngFile()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }
}
