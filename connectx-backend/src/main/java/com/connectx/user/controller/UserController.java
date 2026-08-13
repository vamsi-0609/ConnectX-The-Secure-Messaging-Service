package com.connectx.user.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.user.dto.UserDto;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<UserDto>>> searchUsers(@RequestParam("username") String username) {
        List<UserDto> users = userService.searchUsersByUsername(username);
        return ResponseEntity.ok(ApiResponse.success("User search results", users));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> getCurrentUser(@AuthenticationPrincipal UserPrincipal currentUser) {
        UserDto userDto = userService.getUserById(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Current user profile", userDto));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserDto>> getUserById(@PathVariable Long userId) {
        UserDto userDto = userService.getUserById(userId);
        return ResponseEntity.ok(ApiResponse.success("User details", userDto));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> updateProfile(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody UserProfileUpdateDto updateDto) {
        UserDto updatedUser = userService.updateUserProfile(currentUser.getId(), updateDto);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", updatedUser));
    }

    @PostMapping(value = "/me/profile-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UserDto>> uploadProfilePhoto(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestPart("file") MultipartFile file) {
        UserDto updatedUser = userService.uploadProfilePhoto(currentUser.getId(), file);
        return ResponseEntity.ok(ApiResponse.success("Profile photo updated successfully", updatedUser));
    }

    @DeleteMapping("/me/profile-photo")
    public ResponseEntity<ApiResponse<UserDto>> removeProfilePhoto(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        UserDto updatedUser = userService.removeProfilePhoto(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Profile photo removed successfully", updatedUser));
    }

    @GetMapping("/me/identity-key")
    public ResponseEntity<ApiResponse<com.connectx.user.dto.UserIdentityKeyDto>> getIdentityKey(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        com.connectx.user.dto.UserIdentityKeyDto dto = userService.getIdentityKey(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("User identity key bundle", dto));
    }

    @PostMapping("/me/identity-key")
    public ResponseEntity<ApiResponse<com.connectx.user.dto.UserIdentityKeyDto>> saveIdentityKey(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestBody com.connectx.user.dto.UserIdentityKeyDto dto) {
        com.connectx.user.dto.UserIdentityKeyDto updated = userService.saveIdentityKey(currentUser.getId(), dto);
        return ResponseEntity.ok(ApiResponse.success("User identity key bundle updated", updated));
    }
}
