package com.connectx.group.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.group.dto.CreateGroupInvitationRequestDto;
import com.connectx.group.dto.CreateGroupInvitationResponseDto;
import com.connectx.group.dto.GroupInvitationDto;
import com.connectx.group.service.GroupInvitationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every actor/target identity here comes from @AuthenticationPrincipal, never from a request body
 * or path-supplied user id -- GroupInvitationService and, beneath it, GroupAuthorizationService
 * are the only authorities on who may do what.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupInvitationController {

    private final GroupInvitationService groupInvitationService;

    public GroupInvitationController(GroupInvitationService groupInvitationService) {
        this.groupInvitationService = groupInvitationService;
    }

    @PostMapping("/{groupId}/invitations")
    public ResponseEntity<ApiResponse<CreateGroupInvitationResponseDto>> createInvitation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @Valid @RequestBody CreateGroupInvitationRequestDto dto) {
        CreateGroupInvitationResponseDto result = groupInvitationService.createInvitation(currentUser.getId(), groupId, dto);
        return ResponseEntity.ok(ApiResponse.success("Group invitation processed", result));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public ResponseEntity<ApiResponse<GroupInvitationDto>> acceptInvitation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long invitationId) {
        GroupInvitationDto result = groupInvitationService.acceptInvitation(currentUser.getId(), invitationId);
        return ResponseEntity.ok(ApiResponse.success("Group invitation accepted", result));
    }

    @PostMapping("/invitations/{invitationId}/reject")
    public ResponseEntity<ApiResponse<GroupInvitationDto>> rejectInvitation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long invitationId) {
        GroupInvitationDto result = groupInvitationService.rejectInvitation(currentUser.getId(), invitationId);
        return ResponseEntity.ok(ApiResponse.success("Group invitation rejected", result));
    }

    @PostMapping("/invitations/{invitationId}/cancel")
    public ResponseEntity<ApiResponse<GroupInvitationDto>> cancelInvitation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long invitationId) {
        GroupInvitationDto result = groupInvitationService.cancelInvitation(currentUser.getId(), invitationId);
        return ResponseEntity.ok(ApiResponse.success("Group invitation cancelled", result));
    }

    @GetMapping("/invitations/received")
    public ResponseEntity<ApiResponse<List<GroupInvitationDto>>> getReceivedPendingInvitations(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<GroupInvitationDto> invitations = groupInvitationService.getReceivedPendingInvitations(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Pending received group invitations", invitations));
    }

    @GetMapping("/invitations/sent")
    public ResponseEntity<ApiResponse<List<GroupInvitationDto>>> getSentPendingInvitations(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<GroupInvitationDto> invitations = groupInvitationService.getSentPendingInvitations(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Pending sent group invitations", invitations));
    }
}
