package com.connectx.group.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.group.dto.GroupKeyRequestResultDto;
import com.connectx.group.dto.GroupKeyRotationResultDto;
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

    /**
     * Phase 7B key reconciliation: the caller is telling every OTHER active member's client "please
     * re-wrap the group's CURRENT key for me" -- never a request to rotate, never a request to read
     * anyone else's key material. See {@link GroupKeyService#requestRewrap} for the full rationale
     * and why this is safe (throttled, active-member-only, content-free broadcast).
     */
    @PostMapping("/{groupId}/keys/request")
    public ResponseEntity<ApiResponse<GroupKeyRequestResultDto>> requestRewrap(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        GroupKeyRequestResultDto result = groupKeyService.requestRewrap(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group key reconciliation requested", result));
    }

    /**
     * Phase 7C: last-resort recovery rotation. Called ONLY by groupKeyManager's mint fallback,
     * after a reconciliation request ({@link #requestRewrap}) went unfulfilled within its bounded
     * wait -- never called merely because the requester lacks a local copy without having tried
     * reconciliation first. See {@link GroupKeyService#recoverByRotating} for why this must claim a
     * genuinely NEW version rather than letting the caller mint replacement key material under the
     * version it already knew about (a real cross-member decryption corruption bug found via live
     * multi-device testing).
     */
    @PostMapping("/{groupId}/keys/rotate-for-recovery")
    public ResponseEntity<ApiResponse<GroupKeyRotationResultDto>> rotateForRecovery(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long groupId) {
        int newVersion = groupKeyService.recoverByRotating(currentUser.getId(), groupId);
        return ResponseEntity.ok(ApiResponse.success("Group key rotated for recovery", new GroupKeyRotationResultDto(newVersion)));
    }
}
