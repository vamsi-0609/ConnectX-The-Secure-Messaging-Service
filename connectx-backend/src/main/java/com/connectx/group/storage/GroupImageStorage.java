package com.connectx.group.storage;

import org.springframework.web.multipart.MultipartFile;

public interface GroupImageStorage {

    /**
     * Stores a validated group avatar image for the given group (conversationId) and returns a
     * stable public URL path.
     */
    String store(Long groupId, MultipartFile file);

    /**
     * Removes any stored avatar image files for the given group.
     */
    void delete(Long groupId);
}
