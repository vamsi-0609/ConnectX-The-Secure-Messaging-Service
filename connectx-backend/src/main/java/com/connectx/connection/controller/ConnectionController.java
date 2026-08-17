package com.connectx.connection.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.dto.UserConnectionDto;
import com.connectx.connection.service.ConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<ConnectionRequestDto>> sendRequest(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody SendConnectionRequestDto dto) {
        ConnectionRequestDto result = connectionService.sendRequest(currentUser.getId(), dto);
        return ResponseEntity.ok(ApiResponse.success("Connection request sent", result));
    }

    @GetMapping("/requests/pending")
    public ResponseEntity<ApiResponse<List<ConnectionRequestDto>>> getPendingIncomingRequests(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<ConnectionRequestDto> requests = connectionService.getPendingIncomingRequests(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Pending incoming connection requests", requests));
    }

    @GetMapping("/requests/sent")
    public ResponseEntity<ApiResponse<List<ConnectionRequestDto>>> getSentOutgoingRequests(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<ConnectionRequestDto> requests = connectionService.getSentOutgoingRequests(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Sent outgoing connection requests", requests));
    }

    @PostMapping("/requests/{id}/accept")
    public ResponseEntity<ApiResponse<ConnectionRequestDto>> acceptRequest(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long id) {
        ConnectionRequestDto result = connectionService.acceptRequest(currentUser.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Connection request accepted", result));
    }

    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<ApiResponse<ConnectionRequestDto>> rejectRequest(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long id) {
        ConnectionRequestDto result = connectionService.rejectRequest(currentUser.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Connection request rejected", result));
    }

    @PostMapping("/requests/{id}/cancel")
    public ResponseEntity<ApiResponse<ConnectionRequestDto>> cancelRequest(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable Long id) {
        ConnectionRequestDto result = connectionService.cancelRequest(currentUser.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Connection request cancelled", result));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserConnectionDto>>> getConnections(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<UserConnectionDto> connections = connectionService.getMyConnections(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("Your connections", connections));
    }
}
