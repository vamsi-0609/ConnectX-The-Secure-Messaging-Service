package com.connectx.device.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.device.dto.DeviceResponseDto;
import com.connectx.device.dto.RegisterDeviceDto;
import com.connectx.device.dto.UserPublicKeyDto;
import com.connectx.device.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<DeviceResponseDto>> registerDevice(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody RegisterDeviceDto registerDto) {
        DeviceResponseDto response = deviceService.registerDevice(currentUser.getId(), registerDto);
        return ResponseEntity.ok(ApiResponse.success("Device registered successfully", response));
    }

    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<List<DeviceResponseDto>>> getMyDevices(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<DeviceResponseDto> devices = deviceService.getUserDevices(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("User devices", devices));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<String>> deactivateDevice(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long deviceId) {
        deviceService.deactivateDevice(currentUser.getId(), deviceId);
        return ResponseEntity.ok(ApiResponse.success("Device deactivated successfully", "Deactivated"));
    }

    @PostMapping("/devices/{deviceId}/seen")
    public ResponseEntity<ApiResponse<DeviceResponseDto>> markDeviceSeen(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long deviceId) {
        DeviceResponseDto device = deviceService.markDeviceSeenForUser(currentUser.getId(), deviceId);
        return ResponseEntity.ok(ApiResponse.success("Device marked as seen", device));
    }

    @GetMapping("/users/{userId}/devices/public-keys")
    public ResponseEntity<ApiResponse<List<UserPublicKeyDto>>> getUserPublicKeys(@PathVariable Long userId) {
        List<UserPublicKeyDto> publicKeys = deviceService.getUserPublicKeys(userId);
        return ResponseEntity.ok(ApiResponse.success("User public key endpoints", publicKeys));
    }
}
