package com.connectx.push.service;

import com.connectx.push.dto.PushSubscriptionRequestDto;
import com.connectx.push.entity.UserPushSubscription;
import com.connectx.push.repository.UserPushSubscriptionRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final UserPushSubscriptionRepository pushSubscriptionRepository;
    private final UserRepository userRepository;
    private final HttpClient httpClient;
    private final ExecutorService asyncPushExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private KeyPair vapidKeyPair;
    private String vapidPublicKeyBase64Url;

    public WebPushService(UserPushSubscriptionRepository pushSubscriptionRepository,
                          UserRepository userRepository,
                          @Value("${connectx.push.subject:mailto:admin@connectx.com}") String pushSubject,
                          @Value("${connectx.push.vapid.private-key:}") String configuredPrivateKey,
                          @Value("${connectx.push.vapid.public-key:}") String configuredPublicKey) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.userRepository = userRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        initVapidKeys(configuredPrivateKey, configuredPublicKey);
    }

    /**
     * Initialise VAPID keys.
     * If connectx.push.vapid.private-key and connectx.push.vapid.public-key are configured
     * in application.properties (or environment variables), those are used — giving stable
     * keys across server restarts so existing browser push subscriptions remain valid.
     *
     * If the keys are NOT configured, a fresh pair is generated and the Base64-encoded values
     * are logged at WARN level so the operator can copy them into application.properties.
     * Without persisting the keys, push subscriptions will break on every server restart.
     */
    private synchronized void initVapidKeys(String configuredPrivateKey, String configuredPublicKey) {
        try {
            if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()
                    && configuredPublicKey != null && !configuredPublicKey.isBlank()) {
                // Load persisted keys from configuration
                KeyFactory kf = KeyFactory.getInstance("EC", "BC");

                byte[] privKeyBytes = Base64.getDecoder().decode(configuredPrivateKey.trim());
                PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privKeyBytes));

                byte[] pubKeyBytes = Base64.getDecoder().decode(configuredPublicKey.trim());
                PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

                this.vapidKeyPair = new KeyPair(publicKey, privateKey);
                ECPublicKey ecPub = (ECPublicKey) publicKey;
                byte[] uncompressedPoint = encodeECPublicKeyUncompressed(ecPub);
                this.vapidPublicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressedPoint);

                log.info("[ConnectX WebPush] Loaded VAPID keys from configuration. Push subscriptions will be stable across restarts.");
            } else {
                // No keys configured — generate a new pair for this session
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
                ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
                kpg.initialize(ecSpec, new SecureRandom());
                this.vapidKeyPair = kpg.generateKeyPair();

                ECPublicKey pubKey = (ECPublicKey) vapidKeyPair.getPublic();
                byte[] uncompressedPoint = encodeECPublicKeyUncompressed(pubKey);
                this.vapidPublicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressedPoint);

                // Log the generated keys so the operator can persist them
                String encodedPrivate = Base64.getEncoder().encodeToString(vapidKeyPair.getPrivate().getEncoded());
                String encodedPublic  = Base64.getEncoder().encodeToString(vapidKeyPair.getPublic().getEncoded());
                log.warn("[ConnectX WebPush] *** VAPID keys not configured — generated ephemeral keys. "
                        + "Push subscriptions will BREAK on server restart. "
                        + "Add these to application.properties to make them permanent:");
                log.warn("[ConnectX WebPush] connectx.push.vapid.private-key={}", encodedPrivate);
                log.warn("[ConnectX WebPush] connectx.push.vapid.public-key={}", encodedPublic);
            }
        } catch (Exception e) {
            log.error("[ConnectX WebPush] Failed to initialize VAPID KeyPair:", e);
            throw new IllegalStateException("Failed to initialize VAPID keys", e);
        }
    }

    public String getVapidPublicKey() {
        return vapidPublicKeyBase64Url;
    }

    @Transactional
    public void subscribeUser(Long userId, PushSubscriptionRequestDto dto, String userAgent) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Optional<UserPushSubscription> existing = pushSubscriptionRepository.findByEndpoint(dto.getEndpoint());
        UserPushSubscription sub = existing.orElseGet(UserPushSubscription::new);
        sub.setUser(user);
        sub.setEndpoint(dto.getEndpoint());
        if (dto.getKeys() != null) {
            sub.setP256dhKey(dto.getKeys().getP256dh());
            sub.setAuthKey(dto.getKeys().getAuth());
        }
        sub.setUserAgent(userAgent);

        pushSubscriptionRepository.save(sub);
        log.info("[ConnectX WebPush] Registered push subscription for user {} (endpoint={})", user.getUsername(), dto.getEndpoint().substring(0, Math.min(30, dto.getEndpoint().length())) + "...");
    }

    @Transactional
    public void unsubscribeUser(Long userId, String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            pushSubscriptionRepository.deleteByEndpoint(endpoint);
        } else {
            pushSubscriptionRepository.deleteByUserId(userId);
        }
    }

    public void sendPushToUserAsync(Long recipientUserId, String title, String body, Long conversationId) {
        asyncPushExecutor.submit(() -> {
            try {
                List<UserPushSubscription> subscriptions = pushSubscriptionRepository.findByUserId(recipientUserId);
                if (subscriptions.isEmpty()) {
                    return;
                }

                String payloadJson = String.format(
                        "{\"title\":\"%s\",\"body\":\"%s\",\"conversationId\":%d,\"url\":\"/?conversation=%d\"}",
                        escapeJson(title),
                        escapeJson(body),
                        conversationId,
                        conversationId
                );

                for (UserPushSubscription sub : subscriptions) {
                    dispatchPushNotification(sub, payloadJson);
                }
            } catch (Exception e) {
                log.warn("[ConnectX WebPush] Failed to process async push to user {}", recipientUserId, e);
            }
        });
    }

    private void dispatchPushNotification(UserPushSubscription sub, String payloadJson) {
        try {
            URI endpointUri = URI.create(sub.getEndpoint());
            String origin = endpointUri.getScheme() + "://" + endpointUri.getAuthority();

            String jwtToken = createVapidJwt(origin);
            String authHeader = "vapid t=" + jwtToken + ", k=" + vapidPublicKeyBase64Url;

            byte[] bodyBytes = payloadJson.getBytes(StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpointUri)
                    .header("Authorization", authHeader)
                    .header("TTL", "86400")
                    .header("Urgency", "high")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode == 201 || statusCode == 200 || statusCode == 202) {
                log.info("[ConnectX WebPush] Push notification delivered successfully to endpoint (status {})", statusCode);
            } else if (statusCode == 404 || statusCode == 410) {
                log.info("[ConnectX WebPush] Subscription expired or unsubscribed (status {}). Cleaning up...", statusCode);
                pushSubscriptionRepository.deleteByEndpoint(sub.getEndpoint());
            } else {
                log.warn("[ConnectX WebPush] Push provider returned status {} body {}", statusCode, response.body());
            }
        } catch (Exception e) {
            log.warn("[ConnectX WebPush] Error sending push notification to {}: {}", sub.getEndpoint(), e.getMessage());
        }
    }

    private String createVapidJwt(String origin) throws Exception {
        String headerJson = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";
        long exp = Instant.now().getEpochSecond() + 43200; // 12 hours
        String payloadJson = String.format("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"mailto:admin@connectx.com\"}", origin, exp);

        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String unsignedToken = encodedHeader + "." + encodedPayload;

        Signature signature = Signature.getInstance("SHA256withECDSA", "BC");
        signature.initSign(vapidKeyPair.getPrivate());
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        byte[] derSig = signature.sign();

        byte[] rawSig = derToRawEcdsaSignature(derSig);
        String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSig);

        return unsignedToken + "." + encodedSig;
    }

    private byte[] encodeECPublicKeyUncompressed(ECPublicKey key) throws Exception {
        ECPoint point = key.getW();
        byte[] x = toFixedLengthBytes(point.getAffineX().toByteArray(), 32);
        byte[] y = toFixedLengthBytes(point.getAffineY().toByteArray(), 32);

        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04; // Uncompressed point indicator
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return uncompressed;
    }

    private byte[] toFixedLengthBytes(byte[] array, int length) {
        if (array.length == length) {
            return array;
        }
        if (array.length > length) {
            // Trim leading sign zero byte if present
            byte[] trimmed = new byte[length];
            System.arraycopy(array, array.length - length, trimmed, 0, length);
            return trimmed;
        }
        byte[] padded = new byte[length];
        System.arraycopy(array, 0, padded, length - array.length, array.length);
        return padded;
    }

    private byte[] derToRawEcdsaSignature(byte[] derSig) throws Exception {
        // DER sequence: 0x30 [length] 0x02 [r_len] [r] 0x02 [s_len] [s]
        int offset = 2; // skip 0x30 and length
        if ((derSig[1] & 0x80) != 0) {
            offset += (derSig[1] & 0x7f);
        }

        offset++; // skip 0x02
        int rLen = derSig[offset++];
        byte[] r = new byte[rLen];
        System.arraycopy(derSig, offset, r, 0, rLen);
        offset += rLen;

        offset++; // skip 0x02
        int sLen = derSig[offset++];
        byte[] s = new byte[sLen];
        System.arraycopy(derSig, offset, s, 0, sLen);

        byte[] rawR = toFixedLengthBytes(r, 32);
        byte[] rawS = toFixedLengthBytes(s, 32);

        byte[] rawSig = new byte[64];
        System.arraycopy(rawR, 0, rawSig, 0, 32);
        System.arraycopy(rawS, 0, rawSig, 32, 32);
        return rawSig;
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
