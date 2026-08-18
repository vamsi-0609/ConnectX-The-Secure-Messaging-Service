package com.connectx.group.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.group.dto.TransferOwnershipRequestDto;
import com.connectx.group.dto.UpdateMemberRoleRequestDto;
import com.connectx.group.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Groups Stage 3: role management, removal, and voluntary leave. Every actor identity comes from
 * @AuthenticationPrincipal, never a request body or path-supplied user id for the ACTOR (the
 * target user id in the path is exactly that -- a target, authorized against by
 * GroupAuthorizationService, never trusted as who is performing the action).
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupMembershipController {

    private final GroupService groupService;

    public GroupMembershipController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PatchMapping("/{groupId}/members/{userId}/role")
    public ResponseEntity<ApiResponse<ConversationMemberDto>> changeRole(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateMemberRoleRequestDto dto) {
        ConversationMemberDto result = groupService.changeRole(currentUser.getId(), groupId, userId, dto.getRole());
        return ResponseEntity.ok(ApiResponse.success("Member role updated", result));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<ApiResponse<String>> removeMember(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @PathVariable Long userId) {
        groupService.removeMember(currentUser.getId(), groupId, userId);
        return ResponseEntity.ok(ApiResponse.success("Member removed", "Removed"));
    }

    @PostMapping("/{groupId}/leave")
    public ResponseEntity<ApiResponse<String>> leaveGroup(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        groupService.leaveGroup(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Left group", "Left"));
    }

    // Owner-only. Authorization is entirely GroupService#transferOwnership ->
    // GroupAuthorizationService#requireCanTransferOwnership -- nothing here decides or duplicates it.
    @PostMapping("/{groupId}/ownership/transfer")
    public ResponseEntity<ApiResponse<String>> transferOwnership(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @Valid @RequestBody TransferOwnershipRequestDto dto) {
        groupService.transferOwnership(currentUser.getId(), groupId, dto.getNewOwnerUserId());
        return ResponseEntity.ok(ApiResponse.success("Ownership transferred", "Transferred"));
    }
}
