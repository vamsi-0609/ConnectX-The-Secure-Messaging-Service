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

    // `nonce`/`groupKeyVersion`/`mimeType` are optional form fields, populated only for a GROUP
    // upload of already-client-side-encrypted bytes (see MediaService#uploadConversationMedia for
    // the full contract) -- absent/null for every DIRECT upload, unchanged from before this stage.
    @PostMapping(value = "/conversations/{conversationId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MediaUploadResponseDto>> uploadConversationMedia(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestParam(value = "groupKeyVersion", required = false) String groupKeyVersion,
            @RequestParam(value = "mimeType", required = false) String mimeType) {
        Integer parsedKeyVersion = (groupKeyVersion != null && !groupKeyVersion.isBlank())
                ? Integer.valueOf(groupKeyVersion)
                : null;
        MediaUploadResponseDto response = mediaService.uploadConversationMedia(
                currentUser.getId(), conversationId, file, nonce, parsedKeyVersion, mimeType);
        return ResponseEntity.ok(ApiResponse.success("Media uploaded successfully", response));
    }

    @GetMapping("/media/{mediaId}")
    public ResponseEntity<Resource> getMedia(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long mediaId) {
        MessageMedia media = mediaService.getMediaEntityForUser(currentUser.getId(), mediaId);
        Resource resource = mediaService.getMediaForUser(currentUser.getId(), mediaId);
        String filename = media.getOriginalFilename() != null ? media.getOriginalFilename() : "document";

        // Phase 6A: DIRECT encrypted media has a nonce but (unlike GROUP) no groupKeyVersion, so
        // groupKeyVersion alone can no longer distinguish encrypted from plaintext media -- nonce
        // presence is the actual signal (see MediaService#uploadConversationMedia: it's only ever
        // persisted for a client-supplied-encryption upload, GROUP or DIRECT).
        boolean isEncrypted = media.getNonce() != null && !media.getNonce().isBlank();
        if (isEncrypted) {
            // E2EE media (GROUP or DIRECT): the bytes served here are ciphertext, not the claimed
            // type's real content -- the server cannot verify what they actually decrypt to (that's
            // the whole point of E2EE). Always served as an opaque, forced-download binary regardless
            // of the claimed mimeType, so a browser can never be tricked into directly rendering
            // untrusted bytes inline; the app only ever renders the DECRYPTED result, client-side,
            // after a successful (authenticated) AES-GCM decrypt. See LocalMediaStorage#storeEncrypted.
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".enc\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(media.getMimeType()))
                .body(resource);
    }
}
