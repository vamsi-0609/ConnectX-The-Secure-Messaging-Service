import { deviceApi } from '../api/deviceApi';
import { userApi } from '../api/userApi';
import { User } from '../types';
import { keyManager } from './keyManager';

/**
 * Ensures a valid crypto device & synchronized Account E2EE Identity Key exists for the user.
 *
 * Account Master Identity Sync:
 * 1. Checks backend for user's account master E2EE identity key bundle.
 * 2. If present, imports the master private key into this browser's local WebCrypto vault.
 * 3. If absent (new account/first run), generates an E2EE identity keypair and backs up the
 *    master key bundle to the user's account on the backend.
 *
 * Result: All devices/browsers belonging to the user share the exact same E2EE identity key,
 * allowing 100% of messages to be decrypted seamlessly on any device.
 */
export async function ensureLocalCryptoDevice(user: User): Promise<void> {
  try {
    // 1. Fetch user's account master E2EE identity key bundle from Spring Boot backend
    const identityBundle = await userApi.getIdentityKey().catch(() => null);

    if (identityBundle && identityBundle.masterPrivateKey && identityBundle.masterPublicKey) {
      const localVault = await keyManager.getKeyVault(user.id);

      // If local IndexedDB vault is missing or doesn't match the account master public key -> Sync down!
      if (!localVault || localVault.publicKeyBase64 !== identityBundle.masterPublicKey) {
        console.info(`[ConnectX E2EE] Syncing Account Master E2EE Key for user #${user.id} to this browser...`);
        const masterPrivateKeyObj = await keyManager.importPrivateKeyFromPKCS8(identityBundle.masterPrivateKey);

        if (masterPrivateKeyObj) {
          await keyManager.saveKeyVault(user.id, masterPrivateKeyObj, identityBundle.masterPublicKey);
        }
      }

      // Ensure a matching server device registration exists for this master public key
      const serverDevices = await deviceApi.getMyDevices().catch(() => []);
      let matchedDevice = serverDevices.find(
        (d) => d.active && d.publicKey === identityBundle.masterPublicKey
      );

      if (!matchedDevice) {
        const deviceName = `${navigator.platform || 'Desktop'} Browser`;
        matchedDevice = await deviceApi.registerDevice({
          deviceName,
          publicKey: identityBundle.masterPublicKey,
          keyAlgorithm: 'ECDH-P256',
        });
      }

      await keyManager.saveLocalDevice(user.id, {
        deviceId: matchedDevice.id,
        publicKey: matchedDevice.publicKey,
        keyAlgorithm: matchedDevice.keyAlgorithm,
      });

      await deviceApi.markDeviceSeen(matchedDevice.id).catch(() => {});
      return;
    }

    // 2. If backend does NOT have a master key bundle yet -> initialize or promote local keypair
    let localVault = await keyManager.getKeyVault(user.id);
    let masterPrivateKeyObj: CryptoKey | null = localVault?.privateKey || null;
    let masterPublicKeyBase64: string = localVault?.publicKeyBase64 || '';

    if (!masterPrivateKeyObj || !masterPublicKeyBase64) {
      console.info('[ConnectX E2EE] Generating initial Account Master E2EE Keypair for user', user.id);
      const generated = await keyManager.generateKeyPair(user.id);
      if (!generated.privateKey || !generated.publicKeyBase64) {
        throw new Error('Web Crypto key generation failed. E2EE requires a secure browser crypto context.');
      }
      masterPrivateKeyObj = generated.privateKey;
      masterPublicKeyBase64 = generated.publicKeyBase64;
    }

    // Export private key to PKCS#8 and back up the master key bundle to the user's backend account.
    // saveIdentityKey is create-only server-side (UserService#saveIdentityKey): if the account
    // already has an established master key -- e.g. this branch was reached wrongly because step 1
    // above merely FAILED to fetch it (network blip) rather than it genuinely not existing yet --
    // the response is that EXISTING key, not the one just generated here. Reconciling against the
    // response (rather than trusting what was just generated) is what makes this self-correcting
    // instead of permanently diverging this device's identity from every other one already
    // registered, however this branch was reached. Deliberately not caught-and-swallowed like
    // before: a failure here must abort key setup entirely (the outer try/catch below logs it and a
    // future app load retries) rather than let this device register itself against a key the server
    // never actually accepted.
    const pkcs8Base64 = await keyManager.exportPrivateKey(masterPrivateKeyObj);
    if (!pkcs8Base64 || !masterPublicKeyBase64) {
      throw new Error('Failed to export the generated E2EE keypair.');
    }

    const saved = await userApi.saveIdentityKey({
      masterPublicKey: masterPublicKeyBase64,
      masterPrivateKey: pkcs8Base64,
    });

    if (saved.masterPublicKey && saved.masterPrivateKey && saved.masterPublicKey !== masterPublicKeyBase64) {
      console.info(
        `[ConnectX E2EE] Account #${user.id} already has an established master key from another device -- adopting it instead of the one just generated here.`
      );
      const existingPrivateKeyObj = await keyManager.importPrivateKeyFromPKCS8(saved.masterPrivateKey);
      if (!existingPrivateKeyObj) {
        throw new Error("Failed to import the account's existing master private key.");
      }
      await keyManager.saveKeyVault(user.id, existingPrivateKeyObj, saved.masterPublicKey);
      masterPublicKeyBase64 = saved.masterPublicKey;
    }

    // Register active device on server
    const serverDevices = await deviceApi.getMyDevices().catch(() => []);
    let registeredDevice = serverDevices.find(
      (d) => d.active && d.publicKey === masterPublicKeyBase64
    );

    if (!registeredDevice) {
      const deviceName = `${navigator.platform || 'Desktop'} Browser`;
      registeredDevice = await deviceApi.registerDevice({
        deviceName,
        publicKey: masterPublicKeyBase64,
        keyAlgorithm: 'ECDH-P256',
      });
    }

    await keyManager.saveLocalDevice(user.id, {
      deviceId: registeredDevice.id,
      publicKey: registeredDevice.publicKey,
      keyAlgorithm: registeredDevice.keyAlgorithm,
    });

    await deviceApi.markDeviceSeen(registeredDevice.id).catch(() => {});
  } catch (err) {
    console.error('[ConnectX E2EE] Device session initialization error:', err);
  }
}


