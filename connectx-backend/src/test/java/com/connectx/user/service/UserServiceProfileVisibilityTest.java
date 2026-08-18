package com.connectx.user.service;

import com.connectx.common.exception.ApiException;
import com.connectx.user.dto.UserDto;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for PATCH /users/me's profilePhotoVisibility field (commit 8d3e880).
 * <p>
 * The 500 reported against this endpoint was not a code defect: the running dev backend process
 * predated the commit that added this field to UserProfileUpdateDto, so Jackson rejected the
 * unrecognized JSON property with HttpMessageNotReadableException, which GlobalExceptionHandler
 * maps to a generic 500 -- reproduced and confirmed via direct HTTP requests against both the
 * stale process (500, exact match) and a freshly-restarted one (200, correct persisted value).
 * These tests exist to catch any *real* future regression in this path, follows the same
 * real-MySQL, service-layer, no-mocks conventions as DirectConversationAuthorizationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceProfileVisibilityTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private UserProfileUpdateDto visibilityDto(String profilePhotoVisibility) {
        UserProfileUpdateDto dto = new UserProfileUpdateDto();
        dto.setProfilePhotoVisibility(profilePhotoVisibility);
        return dto;
    }

    @Test
    void newUser_defaultsToEveryone() {
        User user = newUser("pv_default");
        assertEquals("EVERYONE", userService.getUserById(user.getId(), user.getId()).getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_settingEveryoneSucceeds() {
        User user = newUser("pv_everyone");
        UserDto updated = userService.updateUserProfile(user.getId(), visibilityDto("EVERYONE"));
        assertEquals("EVERYONE", updated.getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_settingConnectionsSucceeds() {
        User user = newUser("pv_connections");
        UserDto updated = userService.updateUserProfile(user.getId(), visibilityDto("CONNECTIONS"));
        assertEquals("CONNECTIONS", updated.getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_persistedValueIsReturnedOnSubsequentGet() {
        User user = newUser("pv_persist");
        userService.updateUserProfile(user.getId(), visibilityDto("CONNECTIONS"));

        UserDto reloaded = userService.getUserById(user.getId(), user.getId());
        assertEquals("CONNECTIONS", reloaded.getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_lowercaseValueIsNormalizedAndAccepted() {
        User user = newUser("pv_lower");
        UserDto updated = userService.updateUserProfile(user.getId(), visibilityDto("connections"));
        assertEquals("CONNECTIONS", updated.getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_invalidValueRejectedWithBadRequestNotServerError() {
        User user = newUser("pv_invalid");
        UserProfileUpdateDto dto = visibilityDto("BOGUS");

        ApiException ex = assertThrows(ApiException.class, () -> userService.updateUserProfile(user.getId(), dto));
        assertEquals("INVALID_PHOTO_VISIBILITY", ex.getCode());
    }

    @Test
    void updateProfile_omittingVisibilityPreservesExistingValue() {
        User user = newUser("pv_omit");
        userService.updateUserProfile(user.getId(), visibilityDto("CONNECTIONS"));

        // A second update that doesn't mention profilePhotoVisibility at all (e.g. changing only
        // displayName) must not reset it back to EVERYONE.
        UserProfileUpdateDto displayNameOnly = new UserProfileUpdateDto();
        displayNameOnly.setDisplayName("Renamed Pv Omit");
        UserDto updated = userService.updateUserProfile(user.getId(), displayNameOnly);

        assertEquals("Renamed Pv Omit", updated.getDisplayName());
        assertEquals("CONNECTIONS", updated.getProfilePhotoVisibility());
    }

    @Test
    void updateProfile_otherFieldsStillWorkAlongsideVisibility() {
        User user = newUser("pv_other_fields");
        UserProfileUpdateDto dto = visibilityDto("CONNECTIONS");
        dto.setDisplayName("New Display Name");

        UserDto updated = userService.updateUserProfile(user.getId(), dto);

        assertEquals("New Display Name", updated.getDisplayName());
        assertEquals("CONNECTIONS", updated.getProfilePhotoVisibility());
    }

    @Test
    void existingUserWithNullVisibility_readsAsEveryoneAndCanStillUpdateOtherFields() {
        // Simulates a row that predates this column: @PrePersist's default only fires on the
        // initial INSERT, so explicitly nulling it out and saving again (an UPDATE, which only
        // triggers @PreUpdate) reproduces a genuinely-NULL legacy row without raw JDBC.
        User user = newUser("pv_legacy");
        user.setProfilePhotoVisibility(null);
        userRepository.saveAndFlush(user);

        assertEquals("EVERYONE", userService.getUserById(user.getId(), user.getId()).getProfilePhotoVisibility());

        UserProfileUpdateDto dto = new UserProfileUpdateDto();
        dto.setDisplayName("Legacy Updated");
        UserDto updated = userService.updateUserProfile(user.getId(), dto);

        assertEquals("Legacy Updated", updated.getDisplayName());
        assertEquals("EVERYONE", updated.getProfilePhotoVisibility());
    }
}
