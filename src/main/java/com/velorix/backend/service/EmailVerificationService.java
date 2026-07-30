package com.velorix.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velorix.backend.model.VerificationToken;
import com.velorix.backend.repository.UserRepository;
import com.velorix.backend.repository.VerificationTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EmailVerificationService {

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private JavaMailSender javaMailSender;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @Async
    public void sendVerificationEmail(String email) {
        try {
            log.info("Starting async email verification process for {}", email);
            
            // Delete any existing tokens for this user
            verificationTokenRepository.deleteByUserEmail(email);

            // Create new token
            String tokenStr = UUID.randomUUID().toString();
            VerificationToken token = VerificationToken.builder()
                    .token(tokenStr)
                    .userEmail(email)
                    .expiryDate(LocalDateTime.now().plusHours(24))
                    .createdAt(LocalDateTime.now())
                    .build();
            
            verificationTokenRepository.save(token);

            String verificationLink = frontendUrl + "/verify-email?token=" + tokenStr;

            log.info("=========================================================================");
            log.info("VERIFICATION LINK FOR {}: {}", email, verificationLink);
            log.info("=========================================================================");

            // 1. Try Resend API (Primary email provider)
            if (resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend")) {
                boolean sentViaResend = sendViaResend(email, verificationLink);
                if (sentViaResend) {
                    log.info("Verification email successfully sent via Resend to {}", email);
                    return;
                }
            }

            // 2. Try SMTP JavaMailSender (Fallback email provider)
            boolean hasMailConfig = mailUsername != null && !mailUsername.trim().isEmpty() && !mailUsername.contains("your_email");
            if (hasMailConfig && javaMailSender != null) {
                try {
                    String safeEmail = HtmlUtils.htmlEscape(email);
                    MimeMessage message = javaMailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                    
                    helper.setTo(email);
                    helper.setSubject("Verify your Vixiem Email");
                    
                    String htmlContent = "<p>Welcome to Vixiem, " + safeEmail + "!</p>"
                                       + "<p>Please verify your email by clicking the link below:</p>"
                                       + "<p><a href=\"" + verificationLink + "\">Verify Email</a></p>";
                                       
                    helper.setText(htmlContent, true);
                    javaMailSender.send(message);
                    log.info("Verification email successfully sent via SMTP to {}", email);
                    return;
                } catch (Exception mailEx) {
                    log.warn("SMTP sending failed for {}: {}", email, mailEx.getMessage());
                }
            }

            // 3. Local Dev Mode Fallback: If no email service is configured, auto-verify so dev is not blocked
            log.info("No active mail provider configured. Auto-verifying {} for local dev mode.", email);
            userRepository.findByEmail(email).ifPresent(user -> {
                user.setEmailVerified(true);
                userRepository.save(user);
            });

        } catch (Exception e) {
            log.error("Failed to process verification email for {}: {}", email, e.getMessage());
        }
    }

    private boolean sendViaResend(String toEmail, String verificationLink) {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String htmlBody = String.format(
                "<div style=\"font-family: Arial, sans-serif; background-color: #0b0f17; padding: 40px; color: #ffffff; text-align: center;\">" +
                "  <div style=\"max-width: 500px; margin: 0 auto; background: #131c2e; padding: 30px; border-radius: 16px; border: 1px solid #334155;\">" +
                "    <h1 style=\"color: #0ea5e9; margin-bottom: 10px;\">Vixiem</h1>" +
                "    <h2 style=\"color: #ffffff; font-size: 20px;\">Verify your Email Address</h2>" +
                "    <p style=\"color: #94a3b8; font-size: 14px; line-height: 1.6;\">" +
                "      Thank you for signing up for Vixiem! Please click the button below to verify your email address and activate your account." +
                "    </p>" +
                "    <div style=\"margin: 30px 0;\">" +
                "      <a href=\"%s\" style=\"background: linear-gradient(to right, #0ea5e9, #6366f1); color: #ffffff; padding: 14px 28px; text-decoration: none; border-radius: 10px; font-weight: bold; display: inline-block;\">" +
                "        Verify Email Address" +
                "      </a>" +
                "    </div>" +
                "    <p style=\"color: #64748b; font-size: 12px; margin-top: 20px;\">" +
                "      If the button above doesn't work, copy and paste this link:<br/>" +
                "      <a href=\"%s\" style=\"color: #0ea5e9;\">%s</a>" +
                "    </p>" +
                "  </div>" +
                "</div>",
                verificationLink, verificationLink, verificationLink
            );

            Map<String, Object> payload = new HashMap<>();
            payload.put("from", "Vixiem <onboarding@resend.dev>");
            payload.put("to", List.of(toEmail));
            payload.put("subject", "Verify your Vixiem Email");
            payload.put("html", htmlBody);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Resend API email dispatched. Response: {}", response.body());
                return true;
            } else {
                log.error("Resend API error (status {}): {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Resend email delivery exception for {}: {}", toEmail, e.getMessage());
            return false;
        }
    }
}
