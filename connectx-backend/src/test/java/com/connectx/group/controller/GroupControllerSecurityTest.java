package com.connectx.group.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Groups Stage 7: an unauthenticated caller cannot reach the delete-group endpoint at all -- there
 * is no request path that reaches GroupController without a real, server-validated JWT principal,
 * and the endpoint carries no actor field in its request to forge one with even if there were (the
 * actor comes from @AuthenticationPrincipal only). Same MockMvc/security-filter-layer convention as
 * GroupKeyControllerSecurityTest -- the one boundary in this stage's suite that genuinely needs the
 * HTTP layer rather than a direct service call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deleteGroupWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/groups/1"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }
}
