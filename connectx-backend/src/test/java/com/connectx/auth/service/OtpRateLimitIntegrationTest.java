package com.connectx.auth.service;

import com.connectx.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Verifies the fix for M-03: a second immediate OTP request for the same email is
 * rejected instead of silently invalidating the first one (email-bombing / "reset
 * griefing"), while a request for a *different* email is unaffected. */
@SpringBootTest
@ActiveProfiles("test")
class OtpRateLimitIntegrationTest {

    @Autowired
    private AuthService authService;

    @Test
    void secondImmediateRequestForSameEmailIsRateLimited() {
        String email = "rate_limit_" + System.nanoTime() + "@test.com";

        assertDoesNotThrow(() -> authService.requestForgotPasswordOtp(email), "first request must succeed");

        ApiException ex = assertThrows(ApiException.class, () -> authService.requestForgotPasswordOtp(email),
                "an immediate second request for the same email must be rate-limited");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("OTP_RATE_LIMITED", ex.getCode());
    }

    @Test
    void requestForADifferentEmailIsNotAffectedByAnotherEmailsCooldown() {
        String emailA = "rate_limit_a_" + System.nanoTime() + "@test.com";
        String emailB = "rate_limit_b_" + System.nanoTime() + "@test.com";

        assertDoesNotThrow(() -> authService.requestForgotPasswordOtp(emailA));
        assertDoesNotThrow(() -> authService.requestForgotPasswordOtp(emailB),
                "a different email must not be rate-limited by emailA's cooldown");
    }
}
