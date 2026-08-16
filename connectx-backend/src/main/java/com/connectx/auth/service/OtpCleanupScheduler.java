package com.connectx.auth.service;

import com.connectx.auth.repository.OtpTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * otp_tokens accumulates one row per login/forgot-password/email-change attempt and was
 * never purged. Tokens are functionally useless once expired (10 minute lifetime), so a
 * generous 1-day retention past expiry leaves ample room for any in-flight verification
 * before rows are reclaimed.
 */
@Component
public class OtpCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OtpCleanupScheduler.class);

    private final OtpTokenRepository otpTokenRepository;

    public OtpCleanupScheduler(OtpTokenRepository otpTokenRepository) {
        this.otpTokenRepository = otpTokenRepository;
    }

    @Scheduled(fixedRate = 6, timeUnit = java.util.concurrent.TimeUnit.HOURS)
    @Transactional
    public void purgeExpiredOtpTokens() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        int deleted = otpTokenRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired OTP token(s) older than {}", deleted, cutoff);
        }
    }
}
