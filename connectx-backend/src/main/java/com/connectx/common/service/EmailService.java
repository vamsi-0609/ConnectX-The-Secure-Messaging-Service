package com.connectx.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${connectx.mail.from:noreply@connectx.com}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtpEmail(String recipientEmail, String otpCode, String purpose) {
        String subject = "ConnectX Security Code: " + otpCode;
        String body = String.format(
                "Hello,\n\nYour ConnectX verification code for %s is:\n\n%s\n\n" +
                "This code will expire in 10 minutes. If you did not request this code, please ignore this message.\n\n" +
                "Best regards,\nThe ConnectX Team",
                purpose,
                otpCode
        );

        log.info("[OTP GENERATED] Email={} Code={} Purpose={}", recipientEmail, otpCode, purpose);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(recipientEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            log.info("[OTP SENT] Verification email sent successfully to {}", recipientEmail);
        } catch (Exception e) {
            log.warn("[EMAIL SERVICE WARNING] Could not send email to {}: {}. (Code printed in server logs above)", recipientEmail, e.getMessage());
        }
    }
}
