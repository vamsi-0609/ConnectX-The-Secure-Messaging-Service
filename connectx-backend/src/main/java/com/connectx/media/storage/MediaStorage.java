package com.connectx.media.storage;

import org.springframework.core.io.Resource;

public interface MediaStorage {

    StoredMediaFile store(String storageKey, org.springframework.web.multipart.MultipartFile file);

    Resource load(String storageKey, String mimeType);

    void delete(String storageKey, String mimeType);

    record StoredMediaFile(String storageKey, String mimeType, long fileSizeBytes) {}
}
