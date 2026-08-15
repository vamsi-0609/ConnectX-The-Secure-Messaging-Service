package com.connectx.push.controller;

import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import com.connectx.push.dto.PushSubscriptionRequestDto;
import com.connectx.push.service.WebPushService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/push")
public class PushNotificationController {

    private final WebPushService webPushService;

    public PushNotificationController(WebPushService webPushService) {
        this.webPushService = webPushService;
    }

    @GetMapping("/vapid-public-key")
    public ResponseEntity<ApiResponse<Map<String, String>>> getVapidPublicKey() {
        String publicKey = webPushService.getVapidPublicKey();
        return ResponseEntity.ok(ApiResponse.success("VAPID public key retrieved", Map.of("vapidPublicKey", publicKey)));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody PushSubscriptionRequestDto dto,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        webPushService.subscribeUser(currentUser.getId(), dto, userAgent);
        return ResponseEntity.ok(ApiResponse.success("Push notification subscription registered", null));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(required = false) String endpoint) {
        webPushService.unsubscribeUser(currentUser.getId(), endpoint);
        return ResponseEntity.ok(ApiResponse.success("Push notification subscription removed", null));
    }
}
