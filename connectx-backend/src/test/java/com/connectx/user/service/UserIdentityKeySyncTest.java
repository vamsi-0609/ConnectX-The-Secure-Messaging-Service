package com.connectx.user.service;

import com.connectx.user.dto.UserIdentityKeyDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression coverage for the multi-device E2EE bug: a second device that (incorrectly) believes
 * no account master key exists yet -- e.g. after deviceSession.ts's GET /me/identity-key call
 * merely FAILED, which it collapses to the same "null" as a genuinely-absent bundle -- used to be
 * able to silently overwrite an already-established master key via POST /me/identity-key
 * (UserService#saveIdentityKey had no guard at all). That orphaned every other already-registered
 * device (and every Group key already wrapped for that user) with no error and no recovery path
 * short of re-encrypting everything from scratch -- exactly matching a real report of the same
 * account decrypting fine on one device (mobile) and failing on another (PC).
 * <p>
 * The fix makes saveIdentityKey create-only: once a master key is established, a second write
 * attempt is silently ignored and the EXISTING key is returned instead, so deviceSession.ts's
 * caller can reconcile its local vault against the response and converge on the correct shared
 * key instead of diverging from it -- see that file's own comment on the reconciliation step.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserIdentityKeySyncTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private UserIdentityKeyDto keyDto(String publicKey, String privateKey) {
        return new UserIdentityKeyDto(publicKey, privateKey);
    }

    @Test
    void firstDevice_establishesTheMasterKey() {
        User user = newUser("idkey1");
        assertNull(userService.getIdentityKey(user.getId()).getMasterPublicKey(), "precondition: no key yet");

        UserIdentityKeyDto saved = userService.saveIdentityKey(user.getId(), keyDto("PUB_A", "PRIV_A"));

        assertEquals("PUB_A", saved.getMasterPublicKey());
        assertEquals("PRIV_A", saved.getMasterPrivateKey());
    }

    // The core regression: a second device racing to "initialize" an already-established identity
    // must get back the EXISTING key, not have its own accepted.
    @Test
    void secondDevice_cannotOverwriteAnEstablishedMasterKey() {
        User user = newUser("idkey2");
        userService.saveIdentityKey(user.getId(), keyDto("PUB_MOBILE", "PRIV_MOBILE"));

        UserIdentityKeyDto secondAttempt = userService.saveIdentityKey(user.getId(), keyDto("PUB_PC", "PRIV_PC"));

        assertEquals("PUB_MOBILE", secondAttempt.getMasterPublicKey(), "must return the EXISTING key, not the PC's freshly-generated one");
        assertEquals("PRIV_MOBILE", secondAttempt.getMasterPrivateKey());

        UserIdentityKeyDto reloaded = userService.getIdentityKey(user.getId());
        assertEquals("PUB_MOBILE", reloaded.getMasterPublicKey(), "the stored key must be unchanged by the second device's attempt");
        assertEquals("PRIV_MOBILE", reloaded.getMasterPrivateKey());
    }

    // A third, fourth, ... device racing in later must all still converge on the same original key.
    @Test
    void multipleLaterDevices_allConvergeOnTheOriginalKey() {
        User user = newUser("idkey3");
        userService.saveIdentityKey(user.getId(), keyDto("PUB_ORIGINAL", "PRIV_ORIGINAL"));

        UserIdentityKeyDto third = userService.saveIdentityKey(user.getId(), keyDto("PUB_THIRD", "PRIV_THIRD"));
        UserIdentityKeyDto fourth = userService.saveIdentityKey(user.getId(), keyDto("PUB_FOURTH", "PRIV_FOURTH"));

        assertEquals("PUB_ORIGINAL", third.getMasterPublicKey());
        assertEquals("PUB_ORIGINAL", fourth.getMasterPublicKey());
    }

    // A partially-corrupted state (only one of the two fields set -- shouldn't normally happen, but
    // the guard checks both) is treated as "not yet established" and is repairable by a clean save.
    @Test
    void partiallyCorruptedState_isRepairable() {
        User user = newUser("idkey4");
        User partial = userRepository.findById(user.getId()).orElseThrow();
        partial.setMasterPublicKey("PUB_ONLY");
        userRepository.save(partial);

        UserIdentityKeyDto saved = userService.saveIdentityKey(user.getId(), keyDto("PUB_FULL", "PRIV_FULL"));

        assertEquals("PUB_FULL", saved.getMasterPublicKey(), "a public-key-only partial state must still be treated as not-yet-established");
        assertEquals("PRIV_FULL", saved.getMasterPrivateKey());
    }

    // Sending blank/empty fields on the very first save must not establish a key at all -- the
    // existing "only apply non-blank fields" behavior is preserved by the fix.
    @Test
    void blankFieldsOnFirstSave_establishNothing() {
        User user = newUser("idkey5");
        UserIdentityKeyDto saved = userService.saveIdentityKey(user.getId(), keyDto("", ""));

        assertNull(saved.getMasterPublicKey());
        assertNull(saved.getMasterPrivateKey());
    }
}
