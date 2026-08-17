package com.connectx.block.controller;

import com.connectx.block.dto.UserBlockDto;
import com.connectx.block.service.BlockService;
import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/blocks")
public class BlockController {

    private final BlockService blockService;

    public BlockController(BlockService blockService) {
        this.blockService = blockService;
    }

    @PostMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserBlockDto>> blockUser(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long userId) {
        UserBlockDto result = blockService.blockUser(currentUser.getId(), userId);
        return ResponseEntity.ok(ApiResponse.success("User blocked", result));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<String>> unblockUser(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long userId) {
        blockService.unblockUser(currentUser.getId(), userId);
        return ResponseEntity.ok(ApiResponse.success("User unblocked", "Unblocked"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserBlockDto>>> getMyBlocks(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<UserBlockDto> blocks = blockService.getMyBlocks(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Your blocked users", blocks));
    }
}
