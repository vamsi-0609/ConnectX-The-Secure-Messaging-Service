package com.connectx.push.service;

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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the reported bug: pushing to a subscription whose endpoint has expired (FCM/push
 * provider returns 410) logged "Subscription expired ... Cleaning up..." immediately followed by
 * "No EntityManager with actual transaction available for current thread - cannot reliably
 * process 'remove' call". dispatchPushNotification runs off a virtual-thread executor task (see
 * sendPushToUserAsync), so it never had a Spring-managed transaction to begin with, and the
 * derived deleteByEndpoint query -- unlike JpaRepository's own CRUD methods -- isn't
 * self-transactional. The stale row survived and got retried on every subsequent send.
 * <p>
 * Also verifies the RFC 8291 payload encryption fix: dispatchPushNotification used to send raw
 * plaintext JSON, which real push services/browsers reject or silently drop (no Content-Encoding
 * header, no aes128gcm framing) -- the reason background notifications never reached a fully
 * closed PWA even though the WebSocket-driven foreground path looked fine. This test acts as the
 * "browser": it generates a real subscriber EC keypair, hands its public key/auth secret to the
 * service exactly like a real PushSubscription would, then independently decrypts the captured
 * request body the same way a browser's native Push API does, proving the wire format is correct
 * end-to-end rather than just checking that some bytes were sent.
 * <p>
 * Uses a real embedded HttpServer (not a mock) as the fake push endpoint so the whole
 * async dispatch -> HTTP call -> cleanup path runs for real, the same way ReactionVisibilityTest
 * exercises real MySQL instead of stubbing out the transaction boundary that was actually buggy.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebPushServiceCleanupTest {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private WebPushService webPushService;
    @Autowired
    private UserRepository userRepository;
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

    /** Mirrors what a browser's native Push API does before dispatching the `push` event. */
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

        // Strip the RFC 8188 single-record delimiter (0x02) appended by the sender.
        assertEquals(0x02, recordPlaintext[recordPlaintext.length - 1], "last record delimiter must be 0x02");
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

    private UserPushSubscription saveSubscription(String endpoint, BrowserSubscriber subscriber) {
        User user = userRepository.save(new User(
                "push_" + System.nanoTime(), "push_" + System.nanoTime() + "@test.com", "hash", "Push Tester"));
        UserPushSubscription sub = new UserPushSubscription();
        sub.setUser(user);
        sub.setEndpoint(endpoint);
        sub.setP256dhKey(subscriber.p256dhBase64Url());
        sub.setAuthKey(subscriber.authBase64Url());
        return pushSubscriptionRepository.save(sub);
    }

    @Test
    void expiredSubscriptionIsDeletedWithoutErrorAndIsNotRetried() throws Exception {
        BrowserSubscriber subscriber = generateBrowserSubscriber();
        AtomicInteger hitCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            hitCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(410, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/push";
        UserPushSubscription sub = saveSubscription(endpoint, subscriber);

        webPushService.sendPushToUserAsync(sub.getUser().getId(), "Title", "Body", 1L);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && pushSubscriptionRepository.findByEndpoint(endpoint).isPresent()) {
            Thread.sleep(100);
        }
        assertTrue(pushSubscriptionRepository.findByEndpoint(endpoint).isEmpty(),
                "a subscription rejected with 410 must be deleted from the database (this used to fail with " +
                        "'No EntityManager with actual transaction available')");

        int hitsAfterCleanup = hitCount.get();
        assertTrue(hitsAfterCleanup >= 1, "the fake push endpoint must have received the delivery attempt");

        webPushService.sendPushToUserAsync(sub.getUser().getId(), "Title 2", "Body 2", 1L);
        Thread.sleep(300);
        assertEquals(hitsAfterCleanup, hitCount.get(),
                "a subscription already deleted must not be retried on a later send");
    }

    @Test
    void validSubscriptionReceivesCorrectlyEncryptedNotificationAndIsNotDeleted() throws Exception {
        BrowserSubscriber subscriber = generateBrowserSubscriber();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentEncoding = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/push", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedContentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/push";
        UserPushSubscription sub = saveSubscription(endpoint, subscriber);

        webPushService.sendPushToUserAsync(sub.getUser().getId(), "New message from Alice", "Sent you a message", 42L);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && capturedBody.get() == null) {
            Thread.sleep(100);
        }
        assertTrue(capturedBody.get() != null, "a valid subscription must still receive the push request");
        assertTrue(pushSubscriptionRepository.findByEndpoint(endpoint).isPresent(),
                "a valid (non-410/404) subscription must not be deleted");

        assertEquals("aes128gcm", capturedContentEncoding.get(),
                "the push request must declare aes128gcm content encoding per RFC 8291");

        // Decrypt exactly as a browser would, using ONLY the subscriber's own private key/auth
        // secret (never anything from the server side) -- this proves the encrypted body the
        // server actually sent over the wire is valid RFC 8291 aes128gcm, not just that headers
        // were set.
        String decryptedJson = decryptAes128gcm(capturedBody.get(), subscriber);
        JsonNode payload = new ObjectMapper().readTree(decryptedJson);
        assertEquals("New message from Alice", payload.get("title").asText());
        assertEquals("Sent you a message", payload.get("body").asText());
        assertEquals(42, payload.get("conversationId").asInt());
        assertEquals("/?conversation=42", payload.get("url").asText());
    }

    @Test
    void removeStaleSubscriptionIsIdempotentWhenRowAlreadyGone() {
        assertNoException(() -> webPushService.removeStaleSubscription("https://endpoint.example/does-not-exist"));
        assertNoException(() -> webPushService.removeStaleSubscription("https://endpoint.example/does-not-exist"));
    }

    private void assertNoException(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new AssertionError("cleanup of an already-absent subscription must not throw", e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
