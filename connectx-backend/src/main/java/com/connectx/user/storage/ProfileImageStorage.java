package com.connectx.user.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ProfileImageStorage {

    /**
     * Stores a validated profile image for the given user and returns a stable public URL path.
     */
    String store(Long userId, MultipartFile file);

    /**
     * Removes any stored profile image files for the given user.
     */
    void delete(Long userId);
}
