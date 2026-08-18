package com.connectx.group.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.group.dto.GroupMemberKeyDto;
import com.connectx.group.dto.SubmitGroupMemberKeyRequestDto;
import com.connectx.group.service.GroupKeyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Groups Stage 6C: opaque wrapped-group-key distribution only -- no cryptography happens here or
 * anywhere in the call chain below this controller. The actor is always
 * {@code @AuthenticationPrincipal}, never a request body or path-supplied user id; the request
 * DTO structurally carries no actor field to forge one with. GET .../keys/me takes no target
 * identity at all -- it is always scoped to the authenticated caller.
 */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupKeyController {

    private final GroupKeyService groupKeyService;

    public GroupKeyController(GroupKeyService groupKeyService) {
        this.groupKeyService = groupKeyService;
    }

    @PostMapping("/{groupId}/keys")
    public ResponseEntity<ApiResponse<GroupMemberKeyDto>> submitWrappedKey(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId,
            @Valid @RequestBody SubmitGroupMemberKeyRequestDto dto) {
        GroupMemberKeyDto result = groupKeyService.submitWrappedKey(currentUser.getId(), groupId, dto);
        return ResponseEntity.ok(ApiResponse.success("Group key submitted", result));
    }

    @GetMapping("/{groupId}/keys/me")
    public ResponseEntity<ApiResponse<GroupMemberKeyDto>> getMyWrappedKey(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        GroupMemberKeyDto result = groupKeyService.getMyWrappedKey(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group key", result));
    }
}
