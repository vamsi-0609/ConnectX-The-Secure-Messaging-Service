package com.connectx.group.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.dto.UpdateGroupInfoRequestDto;
import com.connectx.group.dto.UpdateGroupSettingsRequestDto;
import com.connectx.group.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GroupDto>> createGroup(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateGroupRequestDto dto) {
        GroupDto group = groupService.createGroup(currentUser.getId(), dto);
        return ResponseEntity.ok(ApiResponse.success("Group created", group));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupDto>> getGroupDetails(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        GroupDto group = groupService.getGroupDetails(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group details", group));
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<ApiResponse<List<ConversationMemberDto>>> getGroupMembers(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        List<ConversationMemberDto> members = groupService.getGroupMembers(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group members", members));
    }

    @PatchMapping("/{groupId}/settings")
    public ResponseEntity<ApiResponse<GroupDto>> updateSettings(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @RequestBody UpdateGroupSettingsRequestDto dto) {
        GroupDto group = groupService.updateSettings(currentUser.getId(), groupId, dto);
        return ResponseEntity.ok(ApiResponse.success("Group settings updated", group));
    }

    // Name/description editing -- deliberately a separate route from /settings above: this is
    // gated by who_can_edit_group_info (GroupService#updateGroupInfo -> GroupAuthorizationService
    // #requireCanEditGroupInfo, OWNER/ADMIN or ALL_MEMBERS depending on the group's own setting),
    // not the owner-only rule /settings itself uses to change that policy.
    @PatchMapping("/{groupId}/info")
    public ResponseEntity<ApiResponse<GroupDto>> updateGroupInfo(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @RequestBody UpdateGroupInfoRequestDto dto) {
        GroupDto group = groupService.updateGroupInfo(currentUser.getId(), groupId, dto);
        return ResponseEntity.ok(ApiResponse.success("Group info updated", group));
    }

    // Group photo upload/removal. Authorization is entirely GroupService#uploadAvatar/removeAvatar
    // -> GroupAuthorizationService#requireCanEditGroupInfo -- nothing here decides or duplicates
    // that check. Serving the stored image back is GroupImageController's job, a deliberately
    // separate route/namespace from these two mutating endpoints.
    @PostMapping(value = "/{groupId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<GroupDto>> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @RequestPart("file") MultipartFile file) {
        GroupDto group = groupService.uploadAvatar(currentUser.getId(), groupId, file);
        return ResponseEntity.ok(ApiResponse.success("Group photo updated", group));
    }

    @DeleteMapping("/{groupId}/avatar")
    public ResponseEntity<ApiResponse<GroupDto>> removeAvatar(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        GroupDto group = groupService.removeAvatar(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group photo removed", group));
    }

    // Groups Stage 7: owner-only. Authorization is entirely GroupService#deleteGroup ->
    // GroupAuthorizationService#requireOwner -- nothing here decides or duplicates that check.
    @DeleteMapping("/{groupId}")
    public ResponseEntity<ApiResponse<String>> deleteGroup(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        groupService.deleteGroup(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group deleted", "Deleted"));
    }
}
