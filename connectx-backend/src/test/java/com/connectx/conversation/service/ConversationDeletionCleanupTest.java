package com.connectx.conversation.service;

import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.media.dto.MediaUploadResponseDto;
import com.connectx.media.repository.MessageMediaRepository;
import com.connectx.media.service.MediaService;
import com.connectx.message.entity.Message;
import com.connectx.message.entity.MessageReaction;
import com.connectx.message.entity.MessageStar;
import com.connectx.message.repository.MessageReactionRepository;
import com.connectx.message.repository.MessageRepository;
import com.connectx.message.repository.MessageStarRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H-05: deleting a conversation used to only clean up message_user_state, messages,
 * conversation_members, and the conversation row -- it never touched message_reactions,
 * message_stars, or message_media, all of which hold NOT NULL foreign keys back to messages
 * or the conversation itself. Once both members had deleted the chat, the hard-delete branch
 * would throw a foreign-key violation on any conversation that had a starred/reacted-to message
 * or an uploaded image/document, leaving the conversation permanently undeletable for both
 * users. This also verifies the fix doesn't leave the uploaded file behind on disk once its
 * owning conversation is gone (the orphaned-files risk called out for this bug).
 * <p>
 * Real MySQL (not H2) via the shared "test" profile, since what matters here is the actual
 * foreign-key behavior on delete, not just that the Java code compiles against the schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationDeletionCleanupTest {

    @TempDir
    static Path mediaDir;

    @DynamicPropertySource
    static void mediaDirProperty(DynamicPropertyRegistry registry) {
        registry.add("connectx.upload.media-dir", () -> mediaDir.toString());
    }

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private MediaService mediaService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MessageReactionRepository messageReactionRepository;
    @Autowired
    private MessageStarRepository messageStarRepository;
    @Autowired
    private MessageMediaRepository messageMediaRepository;

    @Test
    void deletingConversationWithReactionsStarsAndMediaSucceedsAndRemovesTheFile() throws Exception {
        User userA = userRepository.save(new User(
                "del_a_" + System.nanoTime(), "del_a_" + System.nanoTime() + "@test.com", "hash", "Deleter A"));
        User userB = userRepository.save(new User(
                "del_b_" + System.nanoTime(), "del_b_" + System.nanoTime() + "@test.com", "hash", "Deleter B"));

        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, userA));
        conversationMemberRepository.save(new ConversationMember(conversation, userB));

        Message textMessage = messageRepository.save(new Message(conversation, userA, null, null, "NONE", "ciphertext", "nonce"));
        messageReactionRepository.save(new MessageReaction(textMessage, userB, "👍"));
        messageStarRepository.save(new MessageStar(textMessage, userB));

        byte[] pngBytes = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03 };
        MockMultipartFile mediaFile = new MockMultipartFile("file", "photo.png", "image/png", pngBytes);
        MediaUploadResponseDto uploaded = mediaService.uploadConversationMedia(userA.getId(), conversation.getId(), mediaFile, null, null, null);

        Long conversationId = conversation.getId();
        String storageKey = messageMediaRepository.findById(uploaded.getMediaId()).orElseThrow().getStorageKey();
        assertTrue(fileExistsForKey(storageKey), "uploaded media file must exist on disk before deletion");

        // Both members delete the conversation -- the second delete crosses the
        // "everyone has deleted it" threshold and triggers the hard-delete branch.
        assertDoesNotThrow(() -> conversationService.deleteConversationForUser(userA.getId(), conversationId));
        assertDoesNotThrow(() -> conversationService.deleteConversationForUser(userB.getId(), conversationId));

        assertTrue(conversationRepository.findById(conversationId).isEmpty(), "conversation row must be gone");
        assertTrue(messageMediaRepository.findById(uploaded.getMediaId()).isEmpty(), "message_media row must be gone");
        assertFalse(fileExistsForKey(storageKey), "uploaded media file must be removed from disk, not orphaned");
    }

    private boolean fileExistsForKey(String storageKey) throws Exception {
        if (!Files.isDirectory(mediaDir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(mediaDir)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith(storageKey));
        }
    }
}
