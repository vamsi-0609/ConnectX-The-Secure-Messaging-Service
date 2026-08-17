package com.connectx.message.service;

import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.media.entity.MessageMedia;
import com.connectx.media.repository.MessageMediaRepository;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.entity.MessageType;
import com.connectx.push.entity.UserPushSubscription;
import com.connectx.push.repository.UserPushSubscriptionRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the redesigned push notification payload: sender display name (falling back to
 * username) as the title, a type-appropriate preview as the body, and the sender's avatar as the
 * icon when available -- while proving the privacy requirement holds: TEXT bodies never carry
 * decrypted message content (the server never has it -- see sendMessage's ciphertext handling)
 * and DOCUMENT bodies never carry the caption either, even though captions aren't E2EE today.
 * <p>
 * Sends real messages through MessageService.sendMessage (not a stub), with a real push
 * subscription and a real embedded HttpServer standing in for the push provider, then decrypts
 * the captured aes128gcm body exactly like a browser would -- same technique and rationale as
 * WebPushServiceCleanupTest, extended here to cover the notification content itself rather than
 * just the transport.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessagePushPreviewTest {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MessageService messageService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private MessageMediaRepository messageMediaRepository;
    @Autowired
    private UserPushSubscriptionRepository pushSubscriptionRepository;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record BrowserSubscriber(PrivateKey privateKey, byte[] publicKeyBytes, byte[] authSecret,
                                      String p256dhBase64Url, String authBase64Url) {
    }

    private static BrowserSubscriber generateBrowserSubscriber() throws Exception {
        ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(ecSpec, new SecureRandom());
        KeyPair keyPair = kpg.generateKeyPair();

        byte[] publicKeyBytes = encodeUncompressedPoint((ECPublicKey) keyPair.getPublic());
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        String p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes);
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(authSecret);
        return new BrowserSubscriber(keyPair.getPrivate(), publicKeyBytes, authSecret, p256dh, auth);
    }

    private static String decryptAes128gcm(byte[] body, BrowserSubscriber subscriber) throws Exception {
        byte[] salt = new byte[16];
        System.arraycopy(body, 0, salt, 0, 16);
        int idLen = body[20] & 0xFF;
        byte[] asPublicBytes = new byte[idLen];
        System.arraycopy(body, 21, asPublicBytes, 0, idLen);
        byte[] ciphertext = new byte[body.length - 21 - idLen];
        System.arraycopy(body, 21 + idLen, ciphertext, 0, ciphertext.length);

        ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        PublicKey asPublicKey = keyFactory.generatePublic(
                new ECPublicKeySpec(ecSpec.getCurve().decodePoint(asPublicBytes), ecSpec));

        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(subscriber.privateKey());
        ka.doPhase(asPublicKey, true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] keyInfo = concat(KEY_INFO_PREFIX, subscriber.publicKeyBytes(), asPublicBytes);
        byte[] ikm = hkdf(subscriber.authSecret(), ecdhSecret, keyInfo, 32);
        byte[] cek = hkdf(salt, ikm, CEK_INFO, 16);
        byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] recordPlaintext = cipher.doFinal(ciphertext);
        return new String(recordPlaintext, 0, recordPlaintext.length - 1, StandardCharsets.UTF_8);
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] output = new byte[length];
        hkdf.generateBytes(output, 0, length);
        return output;
    }

    private static byte[] encodeUncompressedPoint(ECPublicKey key) {
        ECPoint point = key.getW();
        byte[] x = toFixedLengthBytes(point.getAffineX().toByteArray(), 32);
        byte[] y = toFixedLengthBytes(point.getAffineY().toByteArray(), 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] toFixedLengthBytes(byte[] array, int length) {
        if (array.length == length) return array;
        if (array.length > length) {
            byte[] trimmed = new byte[length];
            System.arraycopy(array, array.length - length, trimmed, 0, length);
            return trimmed;
        }
        byte[] padded = new byte[length];
        System.arraycopy(array, 0, padded, length - array.length, array.length);
        return padded;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    /** One sender + one recipient, both members of a fresh DIRECT conversation, with a real
     *  push subscription for the recipient pointed at a local fake push provider. */
    private record Scenario(User sender, User recipient, Conversation conversation, BrowserSubscriber subscriber,
                             AtomicReference<byte[]> capturedBody, AtomicInteger hitCount) {
    }

    // MessageService now requires a currently-CONNECTED pair to send into a DIRECT conversation
    // (see MessageConnectionAuthorizationTest) -- this test file is about push-notification
    // payload content, not authorization, so it simply satisfies that precondition.
    private void connect(User a, User b) {
        ConnectionRequestDto req = connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(), req.getId());
    }

    private Scenario setUpScenario(String senderDisplayName, String senderAvatarUrl) throws Exception {
        long n = System.nanoTime();
        User sender = userRepository.save(new User("push_sender_" + n, "push_sender_" + n + "@test.com", "hash", senderDisplayName));
        sender.setProfileImageUrl(senderAvatarUrl);
        sender = userRepository.save(sender);
        User recipient = userRepository.save(new User("push_recipient_" + n, "push_recipient_" + n + "@test.com", "hash", "Recipient"));
        connect(sender, recipient);

        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(conversation, sender));
        conversationMemberRepository.save(new ConversationMember(conversation, recipient));

        BrowserSubscriber subscriber = generateBrowserSubscriber();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicInteger hitCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            hitCount.incrementAndGet();
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/push";

        UserPushSubscription sub = new UserPushSubscription();
        sub.setUser(recipient);
        sub.setEndpoint(endpoint);
        sub.setP256dhKey(subscriber.p256dhBase64Url());
        sub.setAuthKey(subscriber.authBase64Url());
        pushSubscriptionRepository.save(sub);

        return new Scenario(sender, recipient, conversation, subscriber, capturedBody, hitCount);
    }

    private JsonNode awaitDecryptedPayload(Scenario scenario) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && scenario.capturedBody().get() == null) {
            Thread.sleep(100);
        }
        assertTrue(scenario.capturedBody().get() != null, "push provider must have received a request");
        String decryptedJson = decryptAes128gcm(scenario.capturedBody().get(), scenario.subscriber());
        return new ObjectMapper().readTree(decryptedJson);
    }

    @Test
    void textMessagePreviewUsesDisplayNameAndAvatarButNeverDecryptedContent() throws Exception {
        Scenario s = setUpScenario("Alice Wonderland", "/api/v1/profile-images/42");

        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(s.conversation().getId());
        dto.setMessageType(MessageType.TEXT);
        dto.setCiphertext("this-is-genuinely-ciphertext-the-server-cannot-read");
        dto.setNonce("test-nonce");
        messageService.sendMessage(s.sender().getId(), dto);

        JsonNode payload = awaitDecryptedPayload(s);
        assertEquals("Alice Wonderland", payload.get("title").asText(), "title must be the sender's display name");
        assertEquals("New message", payload.get("body").asText(),
                "TEXT body must be a generic, privacy-safe label -- the server never has the plaintext to preview");
        assertFalse(payload.get("body").asText().contains("ciphertext"),
                "the raw ciphertext must never leak into the notification body");
        assertEquals("/api/v1/profile-images/42", payload.get("icon").asText(), "icon must be the sender's avatar URL");
        assertEquals(s.conversation().getId(), payload.get("conversationId").asLong());
    }

    @Test
    void usernameIsUsedWhenSenderHasNoDisplayName() throws Exception {
        Scenario s = setUpScenario(null, null);
        // Blank display name should fall back to username too, not just null.
        s.sender().setDisplayName("   ");
        userRepository.save(s.sender());

        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(s.conversation().getId());
        dto.setMessageType(MessageType.LOCATION);
        dto.setLatitude(12.34);
        dto.setLongitude(56.78);
        messageService.sendMessage(s.sender().getId(), dto);

        JsonNode payload = awaitDecryptedPayload(s);
        assertEquals(s.sender().getUsername(), payload.get("title").asText());
        assertEquals("Location", payload.get("body").asText());
        assertTrue(payload.get("icon") == null, "icon field must be omitted when the sender has no avatar");
    }

    @Test
    void imageAndDocumentPreviewsAreGenericAndNeverIncludeTheCaption() throws Exception {
        Scenario s = setUpScenario("Bob", null);

        MessageMedia photo = new MessageMedia();
        photo.setConversation(s.conversation());
        photo.setUploadedBy(s.sender());
        photo.setStorageKey("test-key-" + System.nanoTime());
        photo.setMimeType("image/png");
        photo.setFileSizeBytes(1024);
        photo = messageMediaRepository.save(photo);

        SendMessageRequestDto imageDto = new SendMessageRequestDto();
        imageDto.setConversationId(s.conversation().getId());
        imageDto.setMessageType(MessageType.IMAGE);
        imageDto.setMediaId(photo.getId());
        messageService.sendMessage(s.sender().getId(), imageDto);

        JsonNode imagePayload = awaitDecryptedPayload(s);
        assertEquals("Photo", imagePayload.get("body").asText());

        s.capturedBody().set(null);
        MessageMedia doc = new MessageMedia();
        doc.setConversation(s.conversation());
        doc.setUploadedBy(s.sender());
        doc.setStorageKey("test-key-doc-" + System.nanoTime());
        doc.setMimeType("application/pdf");
        doc.setFileSizeBytes(2048);
        doc = messageMediaRepository.save(doc);

        SendMessageRequestDto docDto = new SendMessageRequestDto();
        docDto.setConversationId(s.conversation().getId());
        docDto.setMessageType(MessageType.DOCUMENT);
        docDto.setMediaId(doc.getId());
        docDto.setCaption("very private tax document contents");
        messageService.sendMessage(s.sender().getId(), docDto);

        JsonNode docPayload = awaitDecryptedPayload(s);
        assertEquals("Document", docPayload.get("body").asText(),
                "DOCUMENT body must be the generic label, never the caption");
        assertFalse(docPayload.get("body").asText().contains("tax document"),
                "the caption must never leak into the push notification body");
    }

    @Test
    void mutedConversationStillReceivesNoPush() throws Exception {
        Scenario s = setUpScenario("Carol", null);
        conversationMemberRepository.findByConversationIdAndUserId(s.conversation().getId(), s.recipient().getId())
                .ifPresent(member -> {
                    member.setMutedUntil(Instant.now().plus(1, ChronoUnit.DAYS));
                    conversationMemberRepository.save(member);
                });

        SendMessageRequestDto dto = new SendMessageRequestDto();
        dto.setConversationId(s.conversation().getId());
        dto.setMessageType(MessageType.TEXT);
        dto.setCiphertext("irrelevant-ciphertext");
        dto.setNonce("nonce");
        messageService.sendMessage(s.sender().getId(), dto);

        Thread.sleep(500);
        assertEquals(0, s.hitCount().get(), "a muted conversation must not trigger a push notification");
    }
}
