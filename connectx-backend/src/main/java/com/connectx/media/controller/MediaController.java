package com.connectx.media.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.media.dto.MediaUploadResponseDto;
import com.connectx.media.entity.MessageMedia;
import com.connectx.media.service.MediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping(value = "/conversations/{conversationId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MediaUploadResponseDto>> uploadConversationMedia(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId,
            @RequestPart("file") MultipartFile file) {
        MediaUploadResponseDto response = mediaService.uploadConversationMedia(currentUser.getId(), conversationId, file);
        return ResponseEntity.ok(ApiResponse.success("Media uploaded successfully", response));
    }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<Resource> getMedia(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long mediaId) {
        MessageMedia media = mediaService.getMediaEntityForUser(currentUser.getId(), mediaId);
        Resource resource = mediaService.getMediaForUser(currentUser.getId(), mediaId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(media.getMimeType()))
                .body(resource);
    }
}
