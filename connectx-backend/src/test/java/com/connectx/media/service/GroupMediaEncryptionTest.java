package com.connectx.media.service;

import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.group.service.GroupService;
import com.connectx.media.dto.MediaUploadResponseDto;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.MessageType;
import com.connectx.message.service.MessageService;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group media E2EE hardening stage: MediaService's encrypted-upload contract (nonce/groupKeyVersion
 * required and validated for GROUP, rejected for DIRECT), the "removed member can't download old
 * media" fix (GroupAuthorizationService#requireActiveMember, mirroring every other group-scoped
 * read), and MessageService's extension of the groupKeyVersion contract to an encrypted caption on
 * IMAGE/DOCUMENT sends. Same real-MySQL, service-layer conventions as GroupKeyRotationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupMediaEncryptionTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private MediaService mediaService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private UserRepository userRepository;

    // Encrypted "media" content is opaque ciphertext by construction -- no magic-number signature
    // check applies (see LocalMediaStorage#storeEncrypted), so any non-empty byte string is a valid
    // stand-in here; these tests are about the authorization/version contract around it, not about
    // real AES-GCM output.
    private static final byte[] FAKE_CIPHERTEXT = "not-a-real-image-just-fake-ciphertext-bytes".getBytes();

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

    private void addRawMember(Long groupId, User user, GroupRole role) {
        Conversation conversation = conversationRepository.findById(groupId).orElseThrow();
        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        conversationMemberRepository.save(member);
    }

    private int currentVersion(Long groupId) {
        return chatGroupRepository.findById(groupId).orElseThrow().getKeyVersion();
    }

    private MultipartFile encryptedFile() {
        return new MockMultipartFile("file", "encrypted.bin", "application/octet-stream", FAKE_CIPHERTEXT);
    }

    // ==================== GROUP UPLOAD ====================

    @Test
    void groupUpload_withValidEncryption_succeeds() {
        User owner = newUser("gmed1_owner");
        GroupDto group = newGroup(owner, "Media1 Group");

        MediaUploadResponseDto result = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        assertEquals("image/jpeg", result.getMimeType());
        assertEquals("nonce-1", result.getNonce());
        assertEquals(currentVersion(group.getId()), result.getGroupKeyVersion());
    }

    @Test
    void groupUpload_withoutNonce_rejected() {
        User owner = newUser("gmed2_owner");
        GroupDto group = newGroup(owner, "Media2 Group");

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), null, currentVersion(group.getId()), "image/jpeg"));
        assertEquals("ENCRYPTION_REQUIRED", ex.getCode());
    }

    @Test
    void groupUpload_withoutGroupKeyVersion_rejected() {
        User owner = newUser("gmed3_owner");
        GroupDto group = newGroup(owner, "Media3 Group");

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", null, "image/jpeg"));
        assertEquals("ENCRYPTION_REQUIRED", ex.getCode());
    }

    @Test
    void groupUpload_withStaleKeyVersion_rejected() {
        User owner = newUser("gmed4_owner");
        User member = newUser("gmed4_member");
        GroupDto group = newGroup(owner, "Media4 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        int staleVersion = currentVersion(group.getId());
        groupService.removeMember(owner.getId(), group.getId(), member.getId()); // rotates the key

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", staleVersion, "image/jpeg"));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", ex.getCode());
    }

    @Test
    void groupUpload_missingMimeType_rejected() {
        User owner = newUser("gmed5_owner");
        GroupDto group = newGroup(owner, "Media5 Group");

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), ""));
        assertEquals("INVALID_MEDIA", ex.getCode());
    }

    @Test
    void groupUpload_unsupportedClaimedType_rejected() {
        User owner = newUser("gmed6_owner");
        GroupDto group = newGroup(owner, "Media6 Group");

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "text/html"));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getCode());
    }

    // MEMBER under ADMINS_ONLY cannot even upload media -- mirrors requireCanSendMessage's
    // enforcement for TEXT sends, checked before storage rather than only at message-send time.
    @Test
    void groupUpload_memberUnderAdminsOnlyPolicy_rejected() {
        User owner = newUser("gmed7_owner");
        User member = newUser("gmed7_member");
        GroupDto group = newGroup(owner, "Media7 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        var settings = new com.connectx.group.dto.UpdateGroupSettingsRequestDto();
        settings.setWhoCanSendMessages("ADMINS_ONLY");
        groupService.updateSettings(owner.getId(), group.getId(), settings);

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                member.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg"));
        assertEquals("SEND_NOT_PERMITTED", ex.getCode());
    }

    @Test
    void groupUpload_nonMember_rejected() {
        User owner = newUser("gmed8_owner");
        User outsider = newUser("gmed8_outsider");
        GroupDto group = newGroup(owner, "Media8 Group");

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                outsider.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg"));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // ==================== GROUP DOWNLOAD ====================

    @Test
    void groupDownload_activeMember_allowed() {
        User owner = newUser("gmed9_owner");
        User member = newUser("gmed9_member");
        GroupDto group = newGroup(owner, "Media9 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        assertDoesNotThrow(() -> mediaService.getMediaEntityForUser(member.getId(), media.getMediaId()));
    }

    // The core Part 12 regression: a removed member must not still be able to pull old group media.
    @Test
    void groupDownload_removedMember_rejected() {
        User owner = newUser("gmed10_owner");
        User member = newUser("gmed10_member");
        GroupDto group = newGroup(owner, "Media10 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        groupService.removeMember(owner.getId(), group.getId(), member.getId());

        ApiException ex = assertThrows(ApiException.class,
                () -> mediaService.getMediaEntityForUser(member.getId(), media.getMediaId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    @Test
    void groupDownload_neverMember_rejected() {
        User owner = newUser("gmed11_owner");
        User outsider = newUser("gmed11_outsider");
        GroupDto group = newGroup(owner, "Media11 Group");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        ApiException ex = assertThrows(ApiException.class,
                () -> mediaService.getMediaEntityForUser(outsider.getId(), media.getMediaId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // A member of a DIFFERENT group must not be able to download this group's media.
    @Test
    void groupDownload_crossGroupMember_rejected() {
        User ownerA = newUser("gmed12_ownerA");
        User ownerB = newUser("gmed12_ownerB");
        GroupDto groupA = newGroup(ownerA, "Media12 Group A");
        newGroup(ownerB, "Media12 Group B");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                ownerA.getId(), groupA.getId(), encryptedFile(), "nonce-1", currentVersion(groupA.getId()), "image/jpeg");

        ApiException ex = assertThrows(ApiException.class,
                () -> mediaService.getMediaEntityForUser(ownerB.getId(), media.getMediaId()));
        assertEquals("NOT_GROUP_MEMBER", ex.getCode());
    }

    // ==================== DIRECT REGRESSION ====================

    @Test
    void directUpload_stillPlaintext_unaffected() {
        User a = newUser("gmed13_a");
        User b = newUser("gmed13_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        MultipartFile realPng = new MockMultipartFile("file", "photo.png", "image/png",
                java.util.Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="));

        MediaUploadResponseDto result = mediaService.uploadConversationMedia(
                a.getId(), direct.getId(), realPng, null, null, null);

        assertNull(result.getNonce(), "DIRECT media must stay unencrypted -- no nonce persisted");
        assertNull(result.getGroupKeyVersion());
        assertDoesNotThrow(() -> mediaService.getMediaForUser(b.getId(), result.getMediaId()));
    }

    @Test
    void directUpload_withEncryptionFieldsSupplied_rejected() {
        User a = newUser("gmed14_a");
        User b = newUser("gmed14_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));

        ApiException ex = assertThrows(ApiException.class, () -> mediaService.uploadConversationMedia(
                a.getId(), direct.getId(), encryptedFile(), "nonce-1", 1, "image/jpeg"));
        assertEquals("ENCRYPTION_NOT_SUPPORTED", ex.getCode());
    }

    // ==================== MESSAGE SEND: ENCRYPTED CAPTION ====================

    private SendMessageRequestDto imageDto(Long conversationId, Long mediaId, String ciphertext, String nonce, Integer groupKeyVersion) {
        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(conversationId);
        dto.setMessageType(MessageType.IMAGE);
        dto.setMediaId(mediaId);
        dto.setEncryptionAlgorithm("AES-256-GCM");
        dto.setCiphertext(ciphertext);
        dto.setNonce(nonce);
        dto.setGroupKeyVersion(groupKeyVersion);
        return dto;
    }

    @Test
    void groupImageSend_withEncryptedCaption_succeeds() {
        User owner = newUser("gmed15_owner");
        GroupDto group = newGroup(owner, "Media15 Group");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        MessageDto sent = messageService.sendMessage(owner.getId(),
                imageDto(group.getId(), media.getMediaId(), "caption-ciphertext", "caption-nonce", currentVersion(group.getId())));

        assertEquals("caption-ciphertext", sent.getCiphertext());
        assertEquals("caption-nonce", sent.getNonce());
        assertEquals(currentVersion(group.getId()), sent.getGroupKeyVersion());
        assertNull(sent.getCaption(), "plaintext caption must never be set for an encrypted group image");
        assertEquals("nonce-1", sent.getMediaNonce());
    }

    @Test
    void groupImageSend_withNoCaption_succeeds() {
        User owner = newUser("gmed16_owner");
        GroupDto group = newGroup(owner, "Media16 Group");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        MessageDto sent = messageService.sendMessage(owner.getId(),
                imageDto(group.getId(), media.getMediaId(), null, null, null));

        assertEquals("", sent.getCiphertext());
        assertNull(sent.getCaption());
    }

    @Test
    void groupImageSend_withPlaintextCaption_rejected() {
        User owner = newUser("gmed17_owner");
        GroupDto group = newGroup(owner, "Media17 Group");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        SendMessageRequestDto dto = imageDto(group.getId(), media.getMediaId(), null, null, null);
        dto.setCaption("plaintext caption attempt");

        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(owner.getId(), dto));
        assertEquals("PLAINTEXT_CAPTION_NOT_ALLOWED", ex.getCode());
    }

    @Test
    void groupImageSend_captionWithStaleKeyVersion_rejected() {
        User owner = newUser("gmed18_owner");
        User member = newUser("gmed18_member");
        GroupDto group = newGroup(owner, "Media18 Group");
        addRawMember(group.getId(), member, GroupRole.MEMBER);
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");
        int staleVersion = currentVersion(group.getId());
        groupService.removeMember(owner.getId(), group.getId(), member.getId()); // rotates the key

        SendMessageRequestDto dto = imageDto(group.getId(), media.getMediaId(), "ct", "n", staleVersion);
        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(owner.getId(), dto));
        assertEquals("GROUP_KEY_VERSION_MISMATCH", ex.getCode());
    }

    @Test
    void groupImageSend_captionCiphertextWithoutNonce_rejected() {
        User owner = newUser("gmed19_owner");
        GroupDto group = newGroup(owner, "Media19 Group");
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(
                owner.getId(), group.getId(), encryptedFile(), "nonce-1", currentVersion(group.getId()), "image/jpeg");

        SendMessageRequestDto dto = imageDto(group.getId(), media.getMediaId(), "caption-ciphertext", null, currentVersion(group.getId()));
        ApiException ex = assertThrows(ApiException.class, () -> messageService.sendMessage(owner.getId(), dto));
        assertEquals("NONCE_REQUIRED", ex.getCode());
    }

    // DIRECT image sends are completely untouched by any of the GROUP validation above.
    @Test
    void directImageSend_plaintextCaption_stillWorks() {
        User a = newUser("gmed20_a");
        User b = newUser("gmed20_b");
        connect(a, b);
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        MultipartFile realPng = new MockMultipartFile("file", "photo.png", "image/png",
                java.util.Base64.getDecoder().decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="));
        MediaUploadResponseDto media = mediaService.uploadConversationMedia(a.getId(), direct.getId(), realPng, null, null, null);

        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(direct.getId());
        dto.setMessageType(MessageType.IMAGE);
        dto.setMediaId(media.getMediaId());
        dto.setCaption("a plain caption");

        MessageDto sent = messageService.sendMessage(a.getId(), dto);
        assertEquals("a plain caption", sent.getCaption());
        assertEquals("", sent.getCiphertext());
        assertNull(sent.getMediaNonce());
    }
}
