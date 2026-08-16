package com.connectx.conversation.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConversationDto>>> getConversations(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<ConversationDto> conversations = conversationService.getUserConversations(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("User conversations", conversations));
    }

    @PostMapping("/direct")
    public ResponseEntity<ApiResponse<ConversationDto>> createOrGetDirectConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateDirectConversationDto dto) {
        ConversationDto conversation = conversationService.createOrGetDirectConversation(currentUser.getId(), dto);
        return ResponseEntity.ok(ApiResponse.success("Direct conversation retrieved or created", conversation));
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ApiResponse<ConversationDto>> getConversationById(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.getConversationById(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation details", conversation));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<ApiResponse<String>> deleteConversationForUser(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        conversationService.deleteConversationForUser(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation deleted for current user", "Deleted"));
    }

    @PostMapping("/{conversationId}/clear")
    public ResponseEntity<ApiResponse<String>> clearConversationForUser(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        conversationService.clearConversationForUser(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation cleared for current user", "Cleared"));
    }

    @PostMapping("/{conversationId}/pin")
    public ResponseEntity<ApiResponse<ConversationDto>> pinConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.pinConversation(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation pinned", conversation));
    }

    @PostMapping("/{conversationId}/unpin")
    public ResponseEntity<ApiResponse<ConversationDto>> unpinConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.unpinConversation(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation unpinned", conversation));
    }

    @PostMapping("/{conversationId}/mute")
    public ResponseEntity<ApiResponse<ConversationDto>> muteConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        java.time.Instant mutedUntil = null;
        if (body != null && body.containsKey("mutedUntil") && body.get("mutedUntil") != null) {
            try {
                mutedUntil = java.time.Instant.parse(body.get("mutedUntil"));
            } catch (Exception ignored) {}
        }
        ConversationDto conversation = conversationService.muteConversation(currentUser.getId(), conversationId, mutedUntil);
        return ResponseEntity.ok(ApiResponse.success("Conversation muted", conversation));
    }

    @PostMapping("/{conversationId}/unmute")
    public ResponseEntity<ApiResponse<ConversationDto>> unmuteConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.unmuteConversation(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation unmuted", conversation));
    }

    @PostMapping("/{conversationId}/archive")
    public ResponseEntity<ApiResponse<ConversationDto>> archiveConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.archiveConversation(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation archived", conversation));
    }

    @PostMapping("/{conversationId}/unarchive")
    public ResponseEntity<ApiResponse<ConversationDto>> unarchiveConversation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.unarchiveConversation(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation unarchived", conversation));
    }

    @PostMapping("/{conversationId}/mark-unread")
    public ResponseEntity<ApiResponse<ConversationDto>> markConversationUnread(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.markConversationUnread(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation marked unread", conversation));
    }

    @PostMapping("/{conversationId}/mark-read")
    public ResponseEntity<ApiResponse<ConversationDto>> markConversationRead(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        ConversationDto conversation = conversationService.markConversationRead(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation marked read", conversation));
    }
}
