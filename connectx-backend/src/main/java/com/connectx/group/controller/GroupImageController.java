package com.connectx.group.controller;

import com.connectx.common.security.UserPrincipal;
import com.connectx.group.service.GroupAuthorizationService;
import com.connectx.group.storage.LocalGroupImageStorage;
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
 * Mirrors ProfileImageController's enforcement pattern exactly: this route stays permitAll() at
 * the Spring Security layer (an <img src> tag can't send an Authorization header) and instead
 * enforces auth + visibility itself. Visibility here is simpler than a user's profile photo --
 * a group has no per-viewer privacy setting, so "currently an active member of this group" is
 * the entire rule (GroupAuthorizationService#canViewGroup, the same non-throwing membership check
 * every other group read path already uses). Deliberately its own controller/storage/route
 * namespace (group-images, not profile-images) so a group avatar can never be served through, or
 * confused with, a user's profile-image endpoint.
 */
@RestController
@RequestMapping("/api/v1/group-images")
public class GroupImageController {

    private final LocalGroupImageStorage groupImageStorage;
    private final GroupAuthorizationService groupAuthorizationService;

    public GroupImageController(LocalGroupImageStorage groupImageStorage,
                                 GroupAuthorizationService groupAuthorizationService) {
        this.groupImageStorage = groupImageStorage;
        this.groupAuthorizationService = groupAuthorizationService;
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<Resource> getGroupImage(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!groupAuthorizationService.canViewGroup(currentUser.getId(), groupId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path imagePath = groupImageStorage.resolveStoredImagePath(groupId);
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
