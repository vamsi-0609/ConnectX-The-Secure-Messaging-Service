package com.connectx.user.controller;

import com.connectx.user.storage.LocalProfileImageStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/profile-images")
public class ProfileImageController {

    private final LocalProfileImageStorage profileImageStorage;

    public ProfileImageController(LocalProfileImageStorage profileImageStorage) {
        this.profileImageStorage = profileImageStorage;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Resource> getProfileImage(@PathVariable Long userId) {
        Path imagePath = profileImageStorage.resolveStoredImagePath(userId);
        if (imagePath == null) {
            return ResponseEntity.notFound().build();
        }

        String contentType = probeContentType(imagePath);
        FileSystemResource resource = new FileSystemResource(imagePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String probeContentType(Path imagePath) {
        try {
            String probed = Files.probeContentType(imagePath);
            return probed != null ? probed : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        } catch (Exception ex) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}
