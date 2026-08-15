package com.connectx.auth.controller;

import com.connectx.auth.dto.AuthResponse;
import com.connectx.auth.dto.LoginRequest;
import com.connectx.auth.dto.RegisterRequest;
import com.connectx.auth.service.AuthService;
import com.connectx.common.response.ApiResponse;
import com.connectx.common.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("User registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(@AuthenticationPrincipal UserPrincipal currentUser) {
        if (currentUser != null) {
            authService.logout(currentUser.getId());
        }
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", "Logged out"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        AuthResponse response = authService.refresh(refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    @PostMapping("/forgot-password/request-otp")
    public ResponseEntity<ApiResponse<String>> requestForgotPasswordOtp(@Valid @RequestBody com.connectx.auth.dto.ForgotPasswordRequestDto request) {
        authService.requestForgotPasswordOtp(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success("Verification code sent if email is registered", "OTP sent"));
    }

    @PostMapping("/forgot-password/verify-otp")
    public ResponseEntity<ApiResponse<String>> verifyForgotPasswordOtp(@Valid @RequestBody com.connectx.auth.dto.VerifyOtpRequestDto request) {
        authService.verifyForgotPasswordOtp(request.getEmail(), request.getOtpCode());
        return ResponseEntity.ok(ApiResponse.success("Verification code verified successfully", "OTP verified"));
    }

    @PostMapping("/forgot-password/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(@Valid @RequestBody com.connectx.auth.dto.ResetPasswordRequestDto request) {
        authService.resetPasswordWithOtp(request.getEmail(), request.getOtpCode(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password reset successfully. You can now login with your new password.", "Password reset"));
    }
}
