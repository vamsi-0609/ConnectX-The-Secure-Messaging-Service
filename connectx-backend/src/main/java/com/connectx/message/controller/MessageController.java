package com.connectx.message.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> getConversationMessages(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long conversationId) {
        List<MessageDto> messages = messageService.getConversationMessages(currentUser.getId(), conversationId);
        return ResponseEntity.ok(ApiResponse.success("Conversation message history", messages));
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<MessageDto>> sendMessage(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody com.connectx.message.dto.SendMessageRequestDto sendDto) {
        MessageDto savedMessage = messageService.sendMessage(currentUser.getId(), sendDto);
        return ResponseEntity.ok(ApiResponse.success("Message sent successfully", savedMessage));
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<ApiResponse<String>> deleteMessage(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long messageId,
            @RequestParam(name = "deleteForEveryone", defaultValue = "false") boolean deleteForEveryone) {
        messageService.deleteMessage(currentUser.getId(), messageId, deleteForEveryone);
        return ResponseEntity.ok(ApiResponse.success("Message deleted", "Deleted"));
    }

    @PostMapping("/messages/{messageId}/reactions")
    public ResponseEntity<ApiResponse<MessageDto>> addOrUpdateReaction(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long messageId,
            @RequestBody java.util.Map<String, String> body) {
        String reaction = body != null ? body.get("reaction") : null;
        MessageDto updatedMessage = messageService.addOrUpdateReaction(currentUser.getId(), messageId, reaction);
        return ResponseEntity.ok(ApiResponse.success("Reaction updated", updatedMessage));
    }

    @DeleteMapping("/messages/{messageId}/reactions")
    public ResponseEntity<ApiResponse<MessageDto>> removeReaction(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long messageId) {
        MessageDto updatedMessage = messageService.removeReaction(currentUser.getId(), messageId);
        return ResponseEntity.ok(ApiResponse.success("Reaction removed", updatedMessage));
    }
}
