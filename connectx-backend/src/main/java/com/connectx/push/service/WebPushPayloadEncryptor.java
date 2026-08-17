package com.connectx.push.service;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.util.Base64;

/**
 * RFC 8291 ("Message Encryption for Web Push") payload encryption, aes128gcm content coding.
 * A push service (FCM/autopush) and the browser both reject/silently drop any non-empty push
 * body that isn't encrypted this way -- see the WebPushService P0 diagnosis this fixes.
 * <p>
 * Deliberately self-contained: orchestrates only mature, already-vetted primitives already on
 * the classpath (BouncyCastle's ECDH/HKDF, the JDK's AES-GCM) rather than hand-rolling any actual
 * cryptography -- the RFC's key-derivation/framing steps are protocol glue, not crypto, and no
 * dependency already in this project implements that glue.
 */
final class WebPushPayloadEncryptor {

    // Fixed per-message record size for the aes128gcm framing header (RFC 8188 section 2).
    // Every push payload here is a short JSON string sent as a single record, so this only
    // needs to be large enough to hold that one record -- 4096 is the conventional default
    // used by other Web Push implementations and comfortably covers this app's payloads.
    private static final int RECORD_SIZE = 4096;

    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private WebPushPayloadEncryptor() {}

    /**
     * Encrypts {@code plaintext} for delivery to a single push subscription, per RFC 8291.
     *
     * @param p256dhBase64Url the subscription's "p256dh" key (base64url, uncompressed EC point)
     * @param authBase64Url   the subscription's "auth" secret (base64url, 16 bytes)
     * @return the aes128gcm framed body: salt(16) || record_size(4) || keyid_len(1) || keyid || ciphertext+tag
     */
    static byte[] encrypt(String p256dhBase64Url, String authBase64Url, byte[] plaintext) throws GeneralSecurityException {
        byte[] uaPublicBytes = Base64.getUrlDecoder().decode(p256dhBase64Url);
        byte[] authSecret = Base64.getUrlDecoder().decode(authBase64Url);

        ECNamedCurveParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        PublicKey uaPublicKey = keyFactory.generatePublic(
                new ECPublicKeySpec(ecSpec.getCurve().decodePoint(uaPublicBytes), ecSpec));

        // Ephemeral "application server" keypair -- generated fresh per message, per RFC 8291.
        // Unrelated to (and not to be confused with) the VAPID keypair, which only signs the
        // Authorization JWT and is never used for payload encryption.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(ecSpec, new SecureRandom());
        KeyPair asKeyPair = kpg.generateKeyPair();
        byte[] asPublicBytes = encodeUncompressedPoint((ECPublicKey) asKeyPair.getPublic());

        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(asKeyPair.getPrivate());
        ka.doPhase(uaPublicKey, true);
        byte[] ecdhSecret = ka.generateSecret();

        byte[] keyInfo = concat(KEY_INFO_PREFIX, uaPublicBytes, asPublicBytes);
        byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        byte[] contentEncryptionKey = hkdf(salt, ikm, CEK_INFO, 16);
        byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

        // Single-record message: append the RFC 8188 "last record" delimiter (0x02), no padding.
        byte[] recordPlaintext = concat(plaintext, new byte[]{0x02});

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentEncryptionKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(recordPlaintext);

        byte[] header = new byte[16 + 4 + 1 + asPublicBytes.length];
        System.arraycopy(salt, 0, header, 0, 16);
        header[16] = (byte) (RECORD_SIZE >>> 24);
        header[17] = (byte) (RECORD_SIZE >>> 16);
        header[18] = (byte) (RECORD_SIZE >>> 8);
        header[19] = (byte) RECORD_SIZE;
        header[20] = (byte) asPublicBytes.length;
        System.arraycopy(asPublicBytes, 0, header, 21, asPublicBytes.length);

        return concat(header, ciphertext);
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

        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return uncompressed;
    }

    private static byte[] toFixedLengthBytes(byte[] array, int length) {
        if (array.length == length) {
            return array;
        }
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
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }
}
