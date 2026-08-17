package com.connectx.connection.controller;

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
 * Test item 2: unauthenticated requests to the new connection endpoints must be rejected. This
 * is the one Stage 1 test that genuinely needs to exercise the HTTP/security-filter layer rather
 * than calling ConnectionService directly -- every other Stage 1 test is service-layer, matching
 * this codebase's existing convention (no MockMvc/TestRestTemplate usage found anywhere else in
 * the test suite). Scoped to exactly this one boundary rather than introducing broader web-layer
 * testing infrastructure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    // 2. unauthenticated user cannot send request
    @Test
    void sendRequestWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/connections/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\": 1}"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    void listPendingRequestsWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/connections/requests/pending"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    void acceptRequestWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/connections/requests/1/accept"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }
}
