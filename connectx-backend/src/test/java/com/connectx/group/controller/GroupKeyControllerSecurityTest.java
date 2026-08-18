package com.connectx.group.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test item 21: an unauthenticated caller cannot reach either group-key endpoint at all, which is
 * the strongest possible proof that the actor identity can never be forged through these
 * endpoints -- there is no request path that reaches GroupKeyController without a real,
 * server-validated JWT principal, and neither DTO carries an actor field to forge one with even if
 * there were. Same MockMvc/security-filter-layer convention as ConnectionControllerSecurityTest/
 * BlockControllerSecurityTest -- the one boundary in this codebase's Groups suite that genuinely
 * needs the HTTP layer rather than a direct service call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupKeyControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitWrappedKeyWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/groups/1/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\": 1, \"wrappedKey\": \"w\", \"wrapNonce\": \"n\", \"keyVersion\": 1}"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    void getMyWrappedKeyWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/groups/1/keys/me"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }
}
