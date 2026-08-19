package com.connectx.group.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/v1/group-images/** is permitAll() at the Spring Security filter layer (an <img src> tag
 * can't send an Authorization header -- see GroupImageController's own javadoc), so the real
 * enforcement is inside the controller itself. Without a token at all, GroupImageController's
 * currentUser==null check must still reject the request -- same MockMvc/HTTP-layer convention as
 * GroupControllerSecurityTest/GroupKeyControllerSecurityTest for the one check per stage that
 * genuinely needs the filter chain rather than a direct service call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GroupImageControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getGroupImageWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/group-images/1"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403), org.hamcrest.Matchers.is(404))));
    }
}
