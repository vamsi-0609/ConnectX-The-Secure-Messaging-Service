package com.connectx.auth.service;

import com.connectx.auth.dto.AuthResponse;
import com.connectx.auth.dto.LoginRequest;
import com.connectx.auth.dto.RegisterRequest;
import com.connectx.common.exception.ApiException;
import com.connectx.common.security.JwtTokenProvider;
import com.connectx.common.security.UserPrincipal;
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

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
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
}
