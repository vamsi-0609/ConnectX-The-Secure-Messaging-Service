package com.connectx.block.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Test item 2: unauthenticated requests to the new blocking endpoints must be rejected. Mirrors
 * ConnectionControllerSecurityTest -- the one boundary in this codebase's test suite that
 * genuinely needs to exercise the HTTP/security-filter layer rather than calling the service
 * directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlockControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void blockUserWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/blocks/1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(
                        org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    void unblockUserWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/blocks/1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(
                        org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    void listBlocksWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/blocks"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(
                        org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }
}
