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
}
