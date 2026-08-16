package com.connectx.auth.service;

import com.connectx.auth.dto.AuthResponse;
import com.connectx.auth.dto.LoginRequest;
import com.connectx.auth.dto.RegisterRequest;
import com.connectx.auth.entity.OtpToken;
import com.connectx.auth.repository.OtpTokenRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.common.security.JwtTokenProvider;
import com.connectx.common.security.UserPrincipal;
import com.connectx.common.service.EmailService;
import com.connectx.common.util.AfterCommitExecutor;
import com.connectx.user.dto.UserDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final OtpTokenRepository otpTokenRepository;
    private final EmailService emailService;
    private final AfterCommitExecutor afterCommitExecutor;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider,
                       OtpTokenRepository otpTokenRepository,
                       EmailService emailService,
                       AfterCommitExecutor afterCommitExecutor) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.otpTokenRepository = otpTokenRepository;
        this.emailService = emailService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USERNAME_EXISTS", "Username is already taken");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_EXISTS", "Email is already registered");
        }

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = request.getUsername();
        }

        User user = new User(
                request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                displayName
        );
        User savedUser = userRepository.save(user);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        String accessToken = tokenProvider.generateToken(authentication);
        String refreshToken = tokenProvider.generateRefreshToken(savedUser.getId(), savedUser.getUsername());

        return new AuthResponse(accessToken, refreshToken, UserDto.fromEntity(savedUser));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsernameOrEmail(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findById(userPrincipal.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        String accessToken = tokenProvider.generateToken(authentication);
        String refreshToken = tokenProvider.generateRefreshToken(user.getId(), user.getUsername());

        return new AuthResponse(accessToken, refreshToken, UserDto.fromEntity(user));
    }

    @Transactional
    public void logout(Long userId) {
        SecurityContextHolder.clearContext();
    }

    public AuthResponse refresh(String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
        }

        Long userId = tokenProvider.getUserIdFromJWT(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        String newAccessToken = tokenProvider.generateTokenFromUserId(user.getId(), user.getUsername(), 86400000);
        String newRefreshToken = tokenProvider.generateRefreshToken(user.getId(), user.getUsername());

        return new AuthResponse(newAccessToken, newRefreshToken, UserDto.fromEntity(user));
    }

    @Transactional
    public void requestForgotPasswordOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        otpTokenRepository.findByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, "FORGOT_PASSWORD")
                .forEach(token -> {
                    token.setUsed(true);
                    otpTokenRepository.save(token);
                });

        SecureRandom random = new SecureRandom();
        String otpCode = String.format("%06d", random.nextInt(1000000));
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        OtpToken token = new OtpToken(normalizedEmail, otpCode, "FORGOT_PASSWORD", expiresAt);
        otpTokenRepository.save(token);

        if (userRepository.existsByEmail(normalizedEmail)) {
            // Held open until commit, the DB connection used for the OTP-token write above
            // would otherwise stay checked out for the full blocking SMTP round trip too --
            // with a 10-connection pool, a handful of concurrent requests (or a slow/down
            // mail server) could exhaust it and stall unrelated requests app-wide.
            afterCommitExecutor.runAfterCommit(() ->
                    emailService.sendOtpEmail(normalizedEmail, otpCode, "Password Reset"));
        }
    }

    @Transactional
    public void verifyForgotPasswordOtp(String email, String otpCode) {
        String normalizedEmail = email.trim().toLowerCase();
        validateOtpToken(normalizedEmail, otpCode, "FORGOT_PASSWORD");
    }

    @Transactional
    public void resetPasswordWithOtp(String email, String otpCode, String newPassword) {
        String normalizedEmail = email.trim().toLowerCase();
        OtpToken token = validateOtpToken(normalizedEmail, otpCode, "FORGOT_PASSWORD");

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Account not found"));

        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password must be at least 6 characters");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
        userRepository.save(user);

        token.setUsed(true);
        otpTokenRepository.save(token);
    }

    private OtpToken validateOtpToken(String email, String otpCode, String purpose) {
        OtpToken token = otpTokenRepository.findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OTP", "Invalid or expired verification code"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            token.setUsed(true);
            otpTokenRepository.save(token);
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPIRED_OTP", "Verification code has expired. Please request a new code.");
        }

        if (token.getAttempts() >= 5) {
            token.setUsed(true);
            otpTokenRepository.save(token);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_LOCKED", "Too many failed attempts. Please request a new code.");
        }

        if (!token.getOtpCode().equals(otpCode.trim())) {
            token.setAttempts(token.getAttempts() + 1);
            otpTokenRepository.save(token);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OTP", "Incorrect verification code. Please try again.");
        }

        return token;
    }
}
