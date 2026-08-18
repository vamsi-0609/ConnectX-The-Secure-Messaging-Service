package com.connectx.user.controller;

import com.connectx.block.service.BlockService;
import com.connectx.common.security.JwtTokenProvider;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.service.ConnectionService;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.service.ConversationService;
import com.connectx.user.dto.PublicUserDto;
import com.connectx.user.dto.UserDto;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pre-Groups security hardening: verifies the two issues from the final pre-Groups audit are
 * actually closed, at the real HTTP-response-JSON level, not just "the DTO class has no getter".
 * <p>
 * Issue 1 (email leak): PublicUserDto -- returned by GET /users/{id}, user search, and every
 * conversation member -- must never carry email, regardless of who's asking or what relationship
 * exists between the two users. GET /users/me (self) must keep returning it.
 * <p>
 * Issue 2 (JWT-via-profileImageUrl): PATCH /users/me must not be able to set an arbitrary
 * profileImageUrl (the field was removed from UserProfileUpdateDto entirely).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserPrivacySecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private BlockService blockService;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private String tokenFor(User user) {
        return jwtTokenProvider.generateTokenFromUserId(user.getId(), user.getUsername());
    }

    // 1. User A cannot obtain User B's email via GET /users/{id}, whether or not they're connected.
    @Test
    void getUserById_neverExposesEmail_regardlessOfConnectionState() throws Exception {
        User a = newUser("priv_getbyid_a");
        User b = newUser("priv_getbyid_b"); // unique, not connected to a

        mockMvc.perform(get("/api/v1/users/" + b.getId())
                        .header("Authorization", "Bearer " + tokenFor(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(b.getId()))
                .andExpect(jsonPath("$.data.username").value(b.getUsername()))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    // 2. User search results never expose email either.
    @Test
    void searchUsers_neverExposesEmail() throws Exception {
        User a = newUser("priv_search_a");
        User b = newUser("priv_search_targetxyz");

        mockMvc.perform(get("/api/v1/users/search")
                        .param("username", "priv_search_targetxyz")
                        .header("Authorization", "Bearer " + tokenFor(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value(b.getUsername()))
                .andExpect(jsonPath("$.data[0].email").doesNotExist());
    }

    // 3. Conversation member listings never expose email -- neither the other member's, nor the
    // caller's own entry (self email is already available via /users/me; no need to repeat it here).
    @Test
    void conversationMembers_neverExposeEmail() throws Exception {
        User a = newUser("priv_conv_a");
        User b = newUser("priv_conv_b");
        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        var pending = connectionService.getPendingIncomingRequests(b.getId()).get(0);
        connectionService.acceptRequest(b.getId(), pending.getId());

        ConversationDto conv = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));

        mockMvc.perform(get("/api/v1/conversations/" + conv.getId())
                        .header("Authorization", "Bearer " + tokenFor(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[0].user.email").doesNotExist())
                .andExpect(jsonPath("$.data.members[1].user.email").doesNotExist());
    }

    // 4. GET /users/me (self) is unaffected -- still returns the caller's own real email.
    @Test
    void getCurrentUser_stillReturnsOwnEmail() throws Exception {
        User a = newUser("priv_self");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(a.getEmail()));
    }

    // 5. Service-layer proof (belt-and-suspenders alongside the HTTP-level tests above): the two
    // DTO types are structurally distinct, and searchUsersByUsername/getPublicProfile can only ever
    // produce the email-less one.
    @Test
    void publicProfileAndSearch_returnPublicUserDtoNotUserDto() {
        User a = newUser("priv_types_a");
        User b = newUser("priv_types_b");

        PublicUserDto publicProfile = userService.getPublicProfile(b.getId(), a.getId());
        assertEquals(b.getId(), publicProfile.getId());

        List<PublicUserDto> results = userService.searchUsersByUsername(b.getUsername(), a.getId());
        assertFalse(results.isEmpty());

        UserDto ownProfile = userService.getOwnProfile(a.getId());
        assertEquals(a.getEmail(), ownProfile.getEmail());
    }

    // 6. profileImageUrl is structurally gone from UserProfileUpdateDto -- reflection proof there
    // is no way for client JSON to reach a setProfileImageUrl call on this DTO at all.
    @Test
    void userProfileUpdateDto_hasNoProfileImageUrlProperty() {
        assertThrows(NoSuchMethodException.class, () -> UserProfileUpdateDto.class.getMethod("setProfileImageUrl", String.class));
        assertThrows(NoSuchMethodException.class, () -> UserProfileUpdateDto.class.getMethod("getProfileImageUrl"));
    }

    // 7. Sending profileImageUrl in the raw PATCH body never results in it being applied -- whatever
    // HTTP status the (pre-existing, out-of-scope) strict-unknown-property Jackson config produces,
    // the user's stored profileImageUrl must be unaffected by the attempted value.
    @Test
    void patchProfileMe_withProfileImageUrlInBody_neverAppliesIt() throws Exception {
        User a = newUser("priv_patch_img");
        String maliciousUrl = "https://attacker.example/track.png";
        String body = "{\"displayName\":\"Still Works\",\"profileImageUrl\":\"" + maliciousUrl + "\"}";

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(anyOf(is(200), is(400), is(500))));

        User reloaded = userRepository.findById(a.getId()).orElseThrow();
        assertTrue(reloaded.getProfileImageUrl() == null || !reloaded.getProfileImageUrl().equals(maliciousUrl));
    }

    // 8. Legitimate profile updates (no profileImageUrl involved) still work end-to-end over HTTP.
    @Test
    void patchProfileMe_normalUpdate_stillWorks() throws Exception {
        User a = newUser("priv_patch_normal");

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Updated Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Updated Name"))
                .andExpect(jsonPath("$.data.email").value(a.getEmail()));
    }

    // 9. EVERYONE visibility: a ConnectX-owned photo is visible to a stranger (not connected, not
    // blocked). Sets profileImageUrl the same way uploadProfilePhoto does (an internal path) since
    // PATCH /users/me can no longer set it directly (see test 6/7 above).
    private void setInternalProfileImage(User user, String path) {
        user.setProfileImageUrl(path);
        userRepository.saveAndFlush(user);
    }

    @Test
    void publicProfile_everyoneVisibility_photoVisibleToStranger() {
        User owner = newUser("priv_vis_everyone_owner");
        User stranger = newUser("priv_vis_everyone_stranger");
        setInternalProfileImage(owner, "/api/v1/profile-images/" + owner.getId());

        PublicUserDto seenByStranger = userService.getPublicProfile(owner.getId(), stranger.getId());
        assertEquals("/api/v1/profile-images/" + owner.getId(), seenByStranger.getProfileImageUrl());
    }

    // 10. CONNECTIONS visibility: hidden from a stranger, visible once connected.
    @Test
    void publicProfile_connectionsVisibility_hiddenUntilConnected() {
        User owner = newUser("priv_vis_conn_owner");
        User other = newUser("priv_vis_conn_other");
        setInternalProfileImage(owner, "/api/v1/profile-images/" + owner.getId());
        userService.updateUserProfile(owner.getId(), connectionsVisibilityDto());

        assertEquals(null, userService.getPublicProfile(owner.getId(), other.getId()).getProfileImageUrl());

        connectionService.sendRequest(owner.getId(), new SendConnectionRequestDto(other.getId()));
        var pending = connectionService.getPendingIncomingRequests(other.getId()).get(0);
        connectionService.acceptRequest(other.getId(), pending.getId());

        assertEquals("/api/v1/profile-images/" + owner.getId(), userService.getPublicProfile(owner.getId(), other.getId()).getProfileImageUrl());
    }

    // 11. Blocked users cannot bypass visibility -- even with EVERYONE set, a blocked viewer must
    // never see the photo. This is the actual bypass scenario the pre-Groups audit called out.
    @Test
    void publicProfile_blockedViewer_photoHiddenEvenWithEveryoneVisibility() {
        User owner = newUser("priv_vis_blocked_owner");
        User blockedViewer = newUser("priv_vis_blocked_viewer");
        setInternalProfileImage(owner, "/api/v1/profile-images/" + owner.getId());
        // EVERYONE is the default -- confirm it would normally be visible before blocking.
        assertEquals("/api/v1/profile-images/" + owner.getId(),
                userService.getPublicProfile(owner.getId(), blockedViewer.getId()).getProfileImageUrl());

        blockService.blockUser(owner.getId(), blockedViewer.getId());

        assertEquals(null, userService.getPublicProfile(owner.getId(), blockedViewer.getId()).getProfileImageUrl());
    }

    // 12. Existing users with NULL profilePhotoVisibility remain compatible on the public-profile
    // path specifically (getOwnProfile's NULL handling is covered separately in
    // UserServiceProfileVisibilityTest -- this is the other viewer-facing path).
    @Test
    void publicProfile_nullVisibility_treatedAsEveryone() {
        User owner = newUser("priv_vis_null_owner");
        User stranger = newUser("priv_vis_null_stranger");
        setInternalProfileImage(owner, "/api/v1/profile-images/" + owner.getId());
        owner.setProfilePhotoVisibility(null);
        userRepository.saveAndFlush(owner);

        assertEquals("/api/v1/profile-images/" + owner.getId(),
                userService.getPublicProfile(owner.getId(), stranger.getId()).getProfileImageUrl());
    }

    private UserProfileUpdateDto connectionsVisibilityDto() {
        UserProfileUpdateDto dto = new UserProfileUpdateDto();
        dto.setProfilePhotoVisibility("CONNECTIONS");
        return dto;
    }
}
