package com.connectx.user.controller;

import com.connectx.common.security.UserPrincipal;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.ProfileVisibilityService;
import com.connectx.user.storage.LocalProfileImageStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This is the actual enforcement boundary for profile-photo visibility, not just the DTOs that
 * embed a profileImageUrl: the URL pattern is predictable (userId is not a secret), so hiding it
 * from JSON responses alone would not stop a direct fetch. This route stays permitAll() at the
 * Spring Security layer (same reason /ws/** does: an <img src> tag can't send an Authorization
 * header) and instead enforces auth + visibility itself, exactly like WebSocketAuthChannelInterceptor
 * does for the STOMP CONNECT frame. JwtAuthenticationFilter already supports a `?token=` query
 * param fallback (originally added for the WS handshake), which the frontend reuses here so the
 * plain <img> tag can still carry a token.
 */
@RestController
@RequestMapping("/api/v1/profile-images")
public class ProfileImageController {

    private final LocalProfileImageStorage profileImageStorage;
    private final UserRepository userRepository;
    private final ProfileVisibilityService profileVisibilityService;

    public ProfileImageController(LocalProfileImageStorage profileImageStorage,
                                   UserRepository userRepository,
                                   ProfileVisibilityService profileVisibilityService) {
        this.profileImageStorage = profileImageStorage;
        this.userRepository = userRepository;
        this.profileVisibilityService = profileVisibilityService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Resource> getProfileImage(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long userId) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        User owner = userRepository.findById(userId).orElse(null);
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        if (!profileVisibilityService.isProfilePhotoVisible(owner, currentUser.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path imagePath = profileImageStorage.resolveStoredImagePath(userId);
        if (imagePath == null) {
            return ResponseEntity.notFound().build();
        }

        String contentType = probeContentType(imagePath);
        FileSystemResource resource = new FileSystemResource(imagePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
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
