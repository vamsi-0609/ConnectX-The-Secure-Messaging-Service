package com.connectx.user.service;

import com.connectx.auth.entity.OtpToken;
import com.connectx.auth.repository.OtpTokenRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.common.service.EmailService;
import com.connectx.common.util.AfterCommitExecutor;
import com.connectx.user.dto.PublicUserDto;
import com.connectx.user.dto.UserDto;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.entity.GroupAddPrivacy;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.storage.ProfileImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");
    private static final Set<String> VALID_PHOTO_VISIBILITY_VALUES = Set.of(
            ProfileVisibilityService.VISIBILITY_EVERYONE, ProfileVisibilityService.VISIBILITY_CONNECTIONS);
    private static final Set<String> VALID_GROUP_ADD_PRIVACY_VALUES = java.util.Arrays.stream(GroupAddPrivacy.values())
            .map(Enum::name).collect(Collectors.toSet());

    private final UserRepository userRepository;
    private final ProfileImageStorage profileImageStorage;
    private final OtpTokenRepository otpTokenRepository;
    private final EmailService emailService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final ProfileVisibilityService profileVisibilityService;

    public UserService(UserRepository userRepository,
                       ProfileImageStorage profileImageStorage,
                       OtpTokenRepository otpTokenRepository,
                       EmailService emailService,
                       AfterCommitExecutor afterCommitExecutor,
                       ProfileVisibilityService profileVisibilityService) {
        this.userRepository = userRepository;
        this.profileImageStorage = profileImageStorage;
        this.otpTokenRepository = otpTokenRepository;
        this.emailService = emailService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.profileVisibilityService = profileVisibilityService;
    }

    // Full self-profile, including email -- only ever for GET /users/me, never for another user.
    public UserDto getOwnProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        return UserDto.fromEntity(user);
    }

    // Another user's profile, for GET /users/{userId} -- deliberately returns PublicUserDto (no
    // email) regardless of who's asking, since this is a general-purpose user lookup, not a
    // self-profile endpoint. Photo visibility still resolves correctly when viewerId equals
    // userId (ProfileVisibilityService treats self as always-visible).
    public PublicUserDto getPublicProfile(Long userId, Long viewerId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        boolean photoVisible = profileVisibilityService.isProfilePhotoVisible(user, viewerId);
        return PublicUserDto.fromEntity(user, photoVisible);
    }

    private static final int USER_SEARCH_LIMIT = 20;

    // currentUserId excludes any user blocked in either direction from the results (search
    // privacy) -- enforced in the database query itself, not filtered afterward, so a blocked
    // pair's data never reaches this method's caller in the first place.
    public List<PublicUserDto> searchUsersByUsername(String username, Long currentUserId) {
        if (username == null || username.trim().isEmpty()) {
            return List.of();
        }
        List<User> users = userRepository.searchByUsernameExcludingBlockedPairs(
                username.trim(), currentUserId, org.springframework.data.domain.PageRequest.of(0, USER_SEARCH_LIMIT));
        Map<Long, Boolean> photoVisibility = profileVisibilityService.resolvePhotoVisibility(currentUserId, users);
        return users.stream()
                .map(u -> PublicUserDto.fromEntity(u, photoVisibility.getOrDefault(u.getId(), false)))
                .collect(Collectors.toList());
    }

    @Transactional
    public UserDto updateUserProfile(Long userId, UserProfileUpdateDto updateDto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        if (updateDto.getUsername() != null && !updateDto.getUsername().trim().isEmpty()) {
            String newUsername = updateDto.getUsername().trim();
            if (!USERNAME_PATTERN.matcher(newUsername).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "Username must be 3-30 characters containing only letters, numbers, and underscores");
            }
            if (!newUsername.equalsIgnoreCase(user.getUsername()) && userRepository.existsByUsername(newUsername)) {
                throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "Username is already taken");
            }
            user.setUsername(newUsername);
        }

        if (updateDto.getDisplayName() != null && !updateDto.getDisplayName().trim().isEmpty()) {
            String newDisplayName = updateDto.getDisplayName().trim();
            if (newDisplayName.length() > 50) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME", "Display name cannot exceed 50 characters");
            }
            user.setDisplayName(newDisplayName);
        }

        if (updateDto.getGroupAddPrivacy() != null) {
            String privacy = updateDto.getGroupAddPrivacy().trim().toUpperCase();
            if (!VALID_GROUP_ADD_PRIVACY_VALUES.contains(privacy)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_ADD_PRIVACY",
                        "groupAddPrivacy must be one of: " + VALID_GROUP_ADD_PRIVACY_VALUES);
            }
            user.setGroupAddPrivacy(GroupAddPrivacy.valueOf(privacy));
        }

        if (updateDto.getProfilePhotoVisibility() != null) {
            String visibility = updateDto.getProfilePhotoVisibility().trim().toUpperCase();
            if (!VALID_PHOTO_VISIBILITY_VALUES.contains(visibility)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO_VISIBILITY",
                        "profilePhotoVisibility must be one of: " + VALID_PHOTO_VISIBILITY_VALUES);
            }
            user.setProfilePhotoVisibility(visibility);
        }

        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional
    public void requestEmailChangeOtp(Long userId, String newEmail) {
        if (newEmail == null || newEmail.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_REQUIRED", "New email address is required");
        }
        String normalizedEmail = newEmail.trim().toLowerCase();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        if (normalizedEmail.equalsIgnoreCase(user.getEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_EMAIL", "New email must be different from your current email");
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email address is already in use");
        }

        otpTokenRepository.findByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, "EMAIL_CHANGE")
                .forEach(t -> {
                    t.setUsed(true);
                    otpTokenRepository.save(t);
                });

        SecureRandom random = new SecureRandom();
        String otpCode = String.format("%06d", random.nextInt(1000000));
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        OtpToken token = new OtpToken(normalizedEmail, otpCode, "EMAIL_CHANGE", expiresAt);
        otpTokenRepository.save(token);

        afterCommitExecutor.runAfterCommit(() ->
                emailService.sendOtpEmail(normalizedEmail, otpCode, "Email Address Change"));
    }

    @Transactional
    public UserDto verifyEmailChangeOtp(Long userId, String newEmail, String otpCode) {
        String normalizedEmail = newEmail.trim().toLowerCase();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email address is already in use");
        }

        OtpToken token = otpTokenRepository.findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalizedEmail, "EMAIL_CHANGE")
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

        token.setUsed(true);
        otpTokenRepository.save(token);

        user.setEmail(normalizedEmail);
        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional
    public UserDto uploadProfilePhoto(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        String publicPath = profileImageStorage.store(userId, file);
        user.setProfileImageUrl(publicPath + "?v=" + System.currentTimeMillis());
        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional
    public UserDto removeProfilePhoto(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        profileImageStorage.delete(userId);
        user.setProfileImageUrl(null);
        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    public com.connectx.user.dto.UserIdentityKeyDto getIdentityKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        return new com.connectx.user.dto.UserIdentityKeyDto(user.getMasterPublicKey(), user.getMasterPrivateKey());
    }

    /**
     * Create-only. The account's master E2EE identity keypair (deviceSession.ts's "Account Master
     * Identity Sync") is the single shared secret every one of the user's devices must converge on
     * -- every DIRECT message and every Group key ever wrapped for this user is wrapped against
     * whatever public key is stored here. Once established, it must never be silently overwritten:
     * a device that (incorrectly, e.g. after a transient GET /me/identity-key failure it mistook
     * for "no key exists yet") tries to push a freshly-generated keypair here must get back the
     * EXISTING key unchanged, not have its own key accepted -- otherwise every other already-
     * registered device (and every group key already wrapped for this user) is silently orphaned,
     * with no error and no way to recover short of every message re-encrypting from scratch.
     * deviceSession.ts's sync-down path reconciles its local vault against whatever this method
     * actually returns, so a caller that raced or mis-detected "first device" still converges onto
     * the correct shared key instead of diverging from it.
     * <p>
     * Locks the user row first (mirrors ConversationService#createOrGetDirectConversation's
     * identical pessimistic-write + READ_COMMITTED pattern) so two devices racing to initialize the
     * very first identity key for a brand-new account can't both pass the "not yet established"
     * check and each write their own -- the second to acquire the lock re-reads under it and sees
     * the first's already-committed key.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public com.connectx.user.dto.UserIdentityKeyDto saveIdentityKey(Long userId, com.connectx.user.dto.UserIdentityKeyDto dto) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        boolean alreadyEstablished = user.getMasterPublicKey() != null && !user.getMasterPublicKey().isBlank()
                && user.getMasterPrivateKey() != null && !user.getMasterPrivateKey().isBlank();

        if (!alreadyEstablished) {
            if (dto.getMasterPublicKey() != null && !dto.getMasterPublicKey().isBlank()) {
                user.setMasterPublicKey(dto.getMasterPublicKey());
            }
            if (dto.getMasterPrivateKey() != null && !dto.getMasterPrivateKey().isBlank()) {
                user.setMasterPrivateKey(dto.getMasterPrivateKey());
            }
            userRepository.save(user);
        }

        return new com.connectx.user.dto.UserIdentityKeyDto(user.getMasterPublicKey(), user.getMasterPrivateKey());
    }
}
