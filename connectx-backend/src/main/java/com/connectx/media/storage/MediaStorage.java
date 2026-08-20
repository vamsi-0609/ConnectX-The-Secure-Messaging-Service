package com.connectx.media.storage;

import org.springframework.core.io.Resource;

public interface MediaStorage {

    StoredMediaFile store(String storageKey, org.springframework.web.multipart.MultipartFile file);

    // GROUP E2EE media only: the uploaded bytes are ciphertext -- opaque by construction, so the
    // magic-number/whitelist content validation `store` performs (which exists specifically to
    // verify plaintext bytes actually match their claimed type) cannot and must not run here; there
    // is nothing server-side to validate against. `claimedMimeType` is the sender's own claim about
    // what the DECRYPTED content will be, kept only for the app's own icon/preview-type bookkeeping
    // -- never trusted for the stored file's on-disk extension or for the HTTP response's
    // Content-Type (MediaService/MediaController force `application/octet-stream` +
    // `attachment` for every encrypted download, regardless of this claim, so a mislabeled or
    // hostile claim can never cause a browser to directly render untrusted bytes).
    StoredMediaFile storeEncrypted(String storageKey, org.springframework.web.multipart.MultipartFile file, String claimedMimeType);

    Resource load(String storageKey, String mimeType);

    void delete(String storageKey, String mimeType);

    record StoredMediaFile(String storageKey, String mimeType, long fileSizeBytes) {}
}
