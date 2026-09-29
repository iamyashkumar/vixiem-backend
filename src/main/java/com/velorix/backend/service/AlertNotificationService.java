package com.velorix.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class AlertNotificationService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private com.velorix.backend.security.UrlSecurityValidator urlSecurityValidator;

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${frontend.url:https://vixiem.vercel.app}")
    private String frontendUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Send email notification when a new endpoint is registered for monitoring
     */
    @Async
    public void sendEndpointCreatedNotification(ApiEndpoint endpoint) {
        log.info("Sending endpoint created notification for '{}'", endpoint.getName());

        String targetEmail = endpoint.getAlertEmail();
        String username = null;
        if (targetEmail == null || targetEmail.trim().isEmpty()) {
            String userId = endpoint.getUserId();
            if (userId != null && userId.contains("@")) {
                targetEmail = userId;
            } else if (userId != null) {
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isEmpty()) {
                    userOpt = userRepository.findByEmail(userId);
                }
                if (userOpt.isPresent()) {
                    targetEmail = userOpt.get().getEmail();
                    username = userOpt.get().getUsername();
                }
            }
        }

        if (targetEmail == null || !targetEmail.contains("@")) {
            log.warn("No valid destination email for endpoint creation notification '{}'", endpoint.getName());
            return;
        }

        if (username == null || username.trim().isEmpty()) {
            username = targetEmail.split("@")[0];
        }

        String subject = "[Vixiem Alert] Sentinel Activated: Monitored Endpoint Added";
        String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String statusLabel = endpoint.getLastStatus() != null && endpoint.getLastStatus() ? "UP (Operational)" : "INITIALIZING (In Progress)";

        String htmlBody = String.format(
            "<!DOCTYPE html>" +
            "<html><head><meta charset='utf-8'></head>" +
            "<body style='margin:0; padding:0; background-color: #08080a; font-family: -apple-system, BlinkMacSystemFont, Arial, sans-serif; color: #f8fafc;'>" +
            "  <div style='max-width: 580px; margin: 30px auto; background: #0f141f; border-radius: 16px; border: 1px solid rgba(56, 189, 248, 0.3); overflow: hidden; box-shadow: 0 20px 40px rgba(0,0,0,0.6);'>" +
            "    <div style='background: linear-gradient(135deg, #0b111e 0%%, #131c2e 100%%); padding: 28px; border-bottom: 1px solid rgba(255,255,255,0.08); text-align: center;'>" +
            "      <div style='font-size: 24px; font-weight: 900; color: #ffffff;'>Vixiem<span style='color: #38bdf8;'>.</span></div>" +
            "      <div style='display: inline-block; background: rgba(56, 189, 248, 0.15); color: #38bdf8; font-size: 11px; font-weight: 700; padding: 4px 14px; border-radius: 9999px; margin-top: 12px; text-transform: uppercase;'>Sentinel Activated</div>" +
            "      <h1 style='font-size: 20px; font-weight: 800; color: #ffffff; margin: 16px 0 4px;'>Monitored Target Registered ✅</h1>" +
            "      <p style='color: #94a3b8; font-size: 13px; margin: 0;'>Automated health surveillance is now active for this endpoint.</p>" +
            "    </div>" +
            "    <div style='padding: 28px;'>" +
            "      <p style='font-size: 14px; color: #cbd5e1; margin-top: 0;'>Hello <strong>%s</strong>,</p>" +
            "      <p style='font-size: 13px; color: #94a3b8; line-height: 1.6;'>You added a new endpoint to your Vixiem fleet. Our distributed workers will continuously check its health, response latency, and uptime status.</p>" +
            "      <div style='background: #141b2b; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); padding: 18px; margin: 20px 0;'>" +
            "        <table style='width: 100%%; border-collapse: collapse; font-size: 13px;'>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8; width: 35%%;'>Endpoint Name:</td>" +
            "            <td style='padding: 8px 0; font-weight: 700; color: #ffffff;'>%s</td>" +
            "          </tr>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Target URL:</td>" +
            "            <td style='padding: 8px 0; font-family: monospace; color: #38bdf8; word-break: break-all;'>%s</td>" +
            "          </tr>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Check Frequency:</td>" +
            "            <td style='padding: 8px 0; color: #f8fafc;'>Every %d seconds</td>" +
            "          </tr>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Status:</td>" +
            "            <td style='padding: 8px 0; font-weight: bold; color: #10b981;'>%s</td>" +
            "          </tr>" +
            "          <tr>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Active Since:</td>" +
            "            <td style='padding: 8px 0; color: #f8fafc;'>%s</td>" +
            "          </tr>" +
            "        </table>" +
            "      </div>" +
            "      <div style='background: #111827; border-radius: 8px; padding: 14px 18px; border: 1px solid #1f2937; border-left: 3px solid #0284c7; margin: 22px 0 24px; text-align: left;'>" +
            "        <div style='color: #38bdf8; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 6px;'>24/7 Incident Surveillance Active</div>" +
            "        <p style='margin: 0; font-size: 12.5px; color: #94a3b8; line-height: 1.55;'>Continuous health verification is running. In the event of service disruptions, latency spikes, or HTTP errors, automated incident alerts will be dispatched immediately to <span style='color: #f1f5f9; font-weight: 600;'>%s</span>, followed by a recovery confirmation when the endpoint comes back online.</p>" +
            "      </div>" +
            "      <div style='text-align: center; margin: 26px 0 10px;'>" +
            "        <a href='%s/dashboard/endpoints' style='display: inline-block; background: #0284c7; background: linear-gradient(180deg, #0284c7 0%%, #0369a1 100%%); color: #ffffff !important; font-weight: 600; font-size: 13px; text-decoration: none; padding: 11px 24px; border-radius: 6px; border: 1px solid #0284c7; box-shadow: 0 1px 3px rgba(0,0,0,0.35); letter-spacing: 0.01em;'>" +
            "          View in Fleet Dashboard &rarr;" +
            "        </a>" +
            "      </div>" +
            "    </div>" +
            "    <div style='background: #0b111e; padding: 18px 24px; border-top: 1px solid rgba(255,255,255,0.06); text-align: center; font-size: 11px; color: #64748b; line-height: 1.6;'>" +
            "      <div style='font-weight: 600; color: #94a3b8; margin-bottom: 4px;'>Vixiem Cloud Observability Sentinel</div>" +
            "      <div>You received this automated notification because a monitored target was added to your account.<br/>" +
            "      Manage your endpoints at <a href='%s/dashboard/endpoints' style='color: #38bdf8; text-decoration: underline;'>Vixiem Endpoints</a>.</div>" +
            "    </div>" +
            "  </div>" +
            "</body></html>",
            username,
            endpoint.getName(),
            endpoint.getUrl(),
            endpoint.getCheckIntervalSeconds(),
            statusLabel,
            timestampStr,
            targetEmail,
            frontendUrl,
            frontendUrl
        );

        String plainText = "Vixiem Sentinel Notification\n\n" +
                "Monitored endpoint successfully registered: " + endpoint.getName() + "\n" +
                "URL: " + endpoint.getUrl() + "\n" +
                "Interval: Every " + endpoint.getCheckIntervalSeconds() + "s\n" +
                "Status: " + statusLabel + "\n" +
                "Active Since: " + timestampStr + "\n\n" +
                "24/7 Incident Surveillance Active: Real-time incident alerts will be dispatched to " + targetEmail + " if service disruptions occur.\n\n" +
                "View in Fleet Dashboard: " + frontendUrl + "/dashboard/endpoints";

        // Try Resend API
        if (resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend")) {
            boolean sent = sendViaResend(targetEmail, subject, htmlBody, plainText);
            if (sent) {
                log.info("Endpoint creation email sent via Resend API to {} for '{}'", targetEmail, endpoint.getName());
                return;
            }
        }

        // Fallback to JavaMailSender
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(targetEmail);
                message.setSubject(subject);
                message.setText(plainText);
                mailSender.send(message);
                log.info("Endpoint creation email sent via JavaMailSender to {}", targetEmail);
            } catch (Exception e) {
                log.error("Failed to send endpoint creation email via JavaMailSender to {}: {}", targetEmail, e.getMessage());
            }
        }
    }

    @Async
    public void sendDowntimeAlert(ApiEndpoint endpoint, boolean isDown, String errorMessage) {
        log.info("Triggering alert notification for endpoint '{}' (isDown={})", endpoint.getName(), isDown);

        // 1. Send Email Alert (if alertsEnabled is true or custom alertEmail is set)
        boolean emailAlertsRequested = endpoint.isAlertsEnabled() 
                || (endpoint.getAlertEmail() != null && !endpoint.getAlertEmail().trim().isEmpty());

        if (emailAlertsRequested) {
            sendEmailNotification(endpoint, isDown, errorMessage);
        }

        // 2. Send Discord Webhook Alert
        if (endpoint.getDiscordWebhookUrl() != null && !endpoint.getDiscordWebhookUrl().trim().isEmpty()) {
            sendDiscordWebhookNotification(endpoint, isDown, errorMessage);
        }
    }

    /**
     * Send instant test alert for user verification
     */
    public static class DispatchResult {
        private final boolean success;
        private final String message;
        private final String provider;
        private final int statusCode;

        public DispatchResult(boolean success, String message, String provider, int statusCode) {
            this.success = success;
            this.message = message;
            this.provider = provider;
            this.statusCode = statusCode;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getProvider() { return provider; }
        public int getStatusCode() { return statusCode; }
    }

    @Value("${resend.from.email:Vixiem <onboarding@resend.dev>}")
    private String resendFromEmail;

    /**
     * Send instant test alert for user verification with detailed diagnostics
     */
    public DispatchResult sendTestAlert(String targetEmail, String userName) {
        if (targetEmail == null || !targetEmail.contains("@")) {
            return new DispatchResult(false, "Invalid destination email address.", "NONE", 400);
        }

        String subject = "[Vixiem Alert] Test Notification: Delivery Confirmed";
        String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String htmlBody = String.format(
            "<!DOCTYPE html>" +
            "<html><head><meta charset='utf-8'></head>" +
            "<body style='margin:0; padding:0; background-color: #08080a; font-family: -apple-system, BlinkMacSystemFont, Arial, sans-serif; color: #f8fafc;'>" +
            "  <div style='max-width: 560px; margin: 30px auto; background: #0f141f; border-radius: 16px; border: 1px solid rgba(56, 189, 248, 0.3); overflow: hidden; box-shadow: 0 20px 40px rgba(0,0,0,0.6);'>" +
            "    <div style='background: linear-gradient(135deg, #0b111e 0%%, #131c2e 100%%); padding: 28px; border-bottom: 1px solid rgba(255,255,255,0.08); text-align: center;'>" +
            "      <div style='font-size: 22px; font-weight: 900; color: #ffffff;'>Vixiem<span style='color: #38bdf8;'>.</span></div>" +
            "      <div style='display: inline-block; background: rgba(56, 189, 248, 0.15); color: #38bdf8; font-size: 11px; font-weight: 700; padding: 4px 12px; border-radius: 9999px; margin-top: 10px; text-transform: uppercase;'>Alert System Test</div>" +
            "      <h2 style='font-size: 18px; font-weight: 800; color: #ffffff; margin: 16px 0 4px;'>Alert Delivery Verified ✅</h2>" +
            "      <p style='color: #94a3b8; font-size: 13px; margin: 0;'>Your email notifications are actively configured and operational.</p>" +
            "    </div>" +
            "    <div style='padding: 24px; font-size: 13px; line-height: 1.6; color: #cbd5e1;'>" +
            "      <p>Hello <strong>%s</strong>,</p>" +
            "      <p>This test confirms that your Vixiem endpoint monitoring alerts are delivering directly to <strong>%s</strong>.</p>" +
            "      <div style='background: #141b2b; border-radius: 10px; padding: 14px; margin: 18px 0; border: 1px solid rgba(255,255,255,0.06);'>" +
            "        <div style='margin-bottom: 6px;'>• <strong>Trigger Time:</strong> %s</div>" +
            "        <div style='margin-bottom: 6px;'>• <strong>Delivery Channel:</strong> Real-Time HTTPS API (Zero-Drop)</div>" +
            "        <div>• <strong>Active Frequency:</strong> Instant Incident & Recovery Notifications</div>" +
            "      </div>" +
            "      <div style='text-align: center; margin-top: 26px;'>" +
            "        <a href='%s/dashboard' style='display: inline-block; background: #0284c7; background: linear-gradient(180deg, #0284c7 0%%, #0369a1 100%%); color: #ffffff !important; font-weight: 600; font-size: 13px; text-decoration: none; padding: 11px 24px; border-radius: 6px; border: 1px solid #0284c7; box-shadow: 0 1px 3px rgba(0,0,0,0.35); letter-spacing: 0.01em;'>" +
            "          Open Command Center &rarr;" +
            "        </a>" +
            "      </div>" +
            "    </div>" +
            "    <div style='background: #0b111e; padding: 16px; border-top: 1px solid rgba(255,255,255,0.06); text-align: center; font-size: 11px; color: #64748b;'>" +
            "      Vixiem Cloud Observability Sentinel • Test Dispatch" +
            "    </div>" +
            "  </div>" +
            "</body></html>",
            userName != null ? userName : targetEmail.split("@")[0],
            targetEmail, timestampStr, frontendUrl
        );

        boolean hasResendKey = resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend");
        String resendDiagnostic = null;

        if (hasResendKey) {
            try {
                String sender = resendFromEmail != null && !resendFromEmail.trim().isEmpty() ? resendFromEmail.trim() : "Vixiem <onboarding@resend.dev>";
                String plainText = "Vixiem Alert System Notification\n\n" +
                        "Alert delivery verified for " + targetEmail + " at " + timestampStr + ".\n" +
                        "Your endpoint monitoring notifications are operational.\n\n" +
                        "Open dashboard: " + frontendUrl + "/dashboard\n\n" +
                        "Vixiem Cloud Observability • Manage alert preferences: " + frontendUrl + "/dashboard/settings";

                Map<String, Object> payload = new HashMap<>();
                payload.put("from", sender);
                payload.put("to", List.of(targetEmail));
                payload.put("subject", subject);
                payload.put("html", htmlBody);
                payload.put("text", plainText);
                payload.put("reply_to", "support@vixiem.com");

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("X-Auto-Response-Suppress", "All");
                headers.put("X-Entity-Ref-ID", UUID.randomUUID().toString());
                headers.put("List-Unsubscribe", "<" + frontendUrl + "/dashboard/settings>");
                headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                headers.put("Feedback-ID", "vixiem-alerts:render:transactional");
                payload.put("headers", headers);

                String jsonPayload = objectMapper.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.resend.com/emails"))
                        .header("Authorization", "Bearer " + resendApiKey.trim())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Test alert successfully sent via Resend API to {}", targetEmail);
                    return new DispatchResult(true, "Test alert email successfully dispatched to " + targetEmail, "RESEND", response.statusCode());
                } else {
                    String errorText = response.body();
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
                        if (root.has("message")) {
                            errorText = root.get("message").asText();
                        }
                    } catch (Exception ignored) {}
                    log.error("Resend API error (status {}): {}", response.statusCode(), errorText);
                    
                    if (response.statusCode() == 403) {
                        resendDiagnostic = "Resend Sandbox Restriction: " + errorText + " Tip: To send across any email domain, verify a custom domain at resend.com/domains or send the test alert to your registered Resend email.";
                    } else if (response.statusCode() == 401) {
                        resendDiagnostic = "Resend API Key is unauthorized or invalid. Please verify RESEND_API_KEY in Render.";
                    } else {
                        resendDiagnostic = "Resend API error (" + response.statusCode() + "): " + errorText;
                    }
                }
            } catch (Exception e) {
                log.error("Resend alert delivery exception for {}: {}", targetEmail, e.getMessage());
                resendDiagnostic = "Resend connection error: " + e.getMessage();
            }
        }

        // Try SMTP JavaMailSender fallback
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(targetEmail);
                message.setSubject(subject);
                message.setText("Vixiem Alert Notification Test\n\nAlert delivery confirmed for " + targetEmail + " at " + timestampStr);
                mailSender.send(message);
                log.info("Test alert sent successfully via JavaMailSender to {}", targetEmail);
                return new DispatchResult(true, "Test alert email successfully dispatched via SMTP to " + targetEmail, "SMTP", 200);
            } catch (Exception e) {
                log.error("Test alert JavaMailSender failed: {}", e.getMessage());
            }
        }

        String finalMsg;
        if (resendDiagnostic != null) {
            finalMsg = resendDiagnostic;
        } else if (!hasResendKey) {
            finalMsg = "Email service not configured. Please set RESEND_API_KEY or MAIL_USERNAME/MAIL_PASSWORD in Render environment variables.";
        } else {
            finalMsg = "Failed to dispatch test alert email. Please check mail provider settings.";
        }

        return new DispatchResult(false, finalMsg, hasResendKey ? "RESEND" : "NONE", 400);
    }

    private void sendEmailNotification(ApiEndpoint endpoint, boolean isDown, String errorMessage) {
        String targetEmail = endpoint.getAlertEmail();

        if (targetEmail == null || targetEmail.trim().isEmpty()) {
            // Fallback to owner user email
            String userId = endpoint.getUserId();
            if (userId != null && userId.contains("@")) {
                targetEmail = userId;
            } else if (userId != null) {
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isEmpty()) {
                    userOpt = userRepository.findByEmail(userId);
                }
                if (userOpt.isPresent()) {
                    targetEmail = userOpt.get().getEmail();
                }
            }
        }

        if (targetEmail == null || !targetEmail.contains("@")) {
            log.warn("No valid email destination found for endpoint '{}'", endpoint.getName());
            return;
        }

        String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String subject = isDown 
                ? "[Vixiem Alert] Endpoint Incident: " + endpoint.getName() + " is unreachable" 
                : "[Vixiem Alert] Service Restored: " + endpoint.getName() + " is back online";

        String htmlBody = buildAlertHtml(endpoint, isDown, errorMessage, timestampStr);
        String plainText = buildAlertPlainText(endpoint, isDown, errorMessage, timestampStr);

        // 1. Try Resend API first (works on Render free tier over HTTPS 443)
        if (resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend")) {
            boolean sent = sendViaResend(targetEmail, subject, htmlBody, plainText);
            if (sent) {
                log.info("Email alert successfully sent via Resend API to {} for endpoint '{}'", targetEmail, endpoint.getName());
                return;
            }
        }

        // 2. Fallback to JavaMailSender
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(targetEmail);
                message.setSubject(subject);
                message.setText(plainText);
                mailSender.send(message);
                log.info("Email alert sent successfully via JavaMailSender to {} for endpoint '{}'", targetEmail, endpoint.getName());
            } catch (Exception e) {
                log.error("Failed to send email alert for endpoint '{}' via JavaMailSender: {}", endpoint.getName(), e.getMessage());
            }
        } else {
            log.warn("Neither Resend API nor JavaMailSender could dispatch email alert to {}", targetEmail);
        }
    }

    private boolean sendViaResend(String toEmail, String subject, String htmlBody, String plainText) {
        try {
            Map<String, Object> payload = new HashMap<>();
            String sender = resendFromEmail != null && !resendFromEmail.trim().isEmpty() ? resendFromEmail.trim() : "Vixiem <onboarding@resend.dev>";
            payload.put("from", sender);
            payload.put("to", List.of(toEmail));
            payload.put("subject", subject);
            payload.put("html", htmlBody);
            if (plainText != null && !plainText.isBlank()) {
                payload.put("text", plainText);
            }
            payload.put("reply_to", "support@vixiem.com");

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Auto-Response-Suppress", "All");
            headers.put("X-Entity-Ref-ID", UUID.randomUUID().toString());
            headers.put("List-Unsubscribe", "<" + frontendUrl + "/dashboard/settings>");
            headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            headers.put("Feedback-ID", "vixiem-alerts:render:transactional");
            payload.put("headers", headers);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Resend alert dispatched. Status: {}", response.statusCode());
                return true;
            } else {
                log.error("Resend alert API error (status {}): {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Resend alert delivery exception for {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    private String buildAlertHtml(ApiEndpoint endpoint, boolean isDown, String errorMessage, String timestampStr) {
        String badgeColor = isDown ? "rgba(239, 68, 68, 0.15)" : "rgba(16, 185, 129, 0.15)";
        String badgeTextColor = isDown ? "#ef4444" : "#10b981";
        String statusText = isDown ? "🔴 INCIDENT: ENDPOINT DOWN" : "🟢 RECOVERED: ENDPOINT OPERATIONAL";
        String headline = isDown ? "Endpoint Outage Detected" : "Service Restored to Normal";

        return String.format(
            "<!DOCTYPE html>" +
            "<html><head><meta charset='utf-8'></head>" +
            "<body style='margin:0; padding:0; background-color: #08080a; font-family: -apple-system, BlinkMacSystemFont, Arial, sans-serif; color: #f8fafc;'>" +
            "  <div style='max-width: 580px; margin: 30px auto; background: #0f141f; border-radius: 16px; border: 1px solid rgba(255, 255, 255, 0.1); overflow: hidden; box-shadow: 0 20px 40px rgba(0,0,0,0.6);'>" +
            "    <div style='background: linear-gradient(135deg, #0b111e 0%%, #131c2e 100%%); padding: 28px; border-bottom: 1px solid rgba(255,255,255,0.08); text-align: center;'>" +
            "      <div style='font-size: 24px; font-weight: 900; color: #ffffff;'>Vixiem<span style='color: #38bdf8;'>.</span></div>" +
            "      <div style='display: inline-block; background: %s; color: %s; font-size: 11px; font-weight: 700; padding: 4px 14px; border-radius: 9999px; margin-top: 12px; letter-spacing: 0.05em;'>%s</div>" +
            "      <h1 style='font-size: 20px; font-weight: 800; color: #ffffff; margin: 16px 0 4px;'>%s</h1>" +
            "      <p style='color: #94a3b8; font-size: 13px; margin: 0;'>Monitored Endpoint: <strong>%s</strong></p>" +
            "    </div>" +
            "    <div style='padding: 28px;'>" +
            "      <div style='background: #141b2b; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); padding: 18px; margin-bottom: 24px;'>" +
            "        <table style='width: 100%%; border-collapse: collapse; font-size: 13px;'>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8; width: 35%%;'>Endpoint URL:</td>" +
            "            <td style='padding: 8px 0; font-family: monospace; color: #38bdf8; font-weight: 600; word-break: break-all;'>%s</td>" +
            "          </tr>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Status:</td>" +
            "            <td style='padding: 8px 0; font-weight: bold; color: %s;'>%s</td>" +
            "          </tr>" +
            "          <tr style='border-bottom: 1px solid rgba(255,255,255,0.05);'>" +
            "            <td style='padding: 8px 0; color: #94a3b8;'>Timestamp:</td>" +
            "            <td style='padding: 8px 0; color: #f8fafc;'>%s</td>" +
            "          </tr>" +
            "          %s" +
            "        </table>" +
            "      </div>" +
            "      <div style='text-align: center; margin-top: 26px;'>" +
            "        <a href='%s/dashboard' style='display: inline-block; background: #0284c7; background: linear-gradient(180deg, #0284c7 0%%, #0369a1 100%%); color: #ffffff !important; font-weight: 600; font-size: 13px; text-decoration: none; padding: 11px 24px; border-radius: 6px; border: 1px solid #0284c7; box-shadow: 0 1px 3px rgba(0,0,0,0.35); letter-spacing: 0.01em;'>" +
            "          Open Command Center &rarr;" +
            "        </a>" +
            "      </div>" +
            "    </div>" +
            "    <div style='background: #0b111e; padding: 18px; border-top: 1px solid rgba(255,255,255,0.06); text-align: center; font-size: 11px; color: #64748b;'>" +
            "      Vixiem Cloud Observability Sentinel • Automated Telemetry Alert" +
            "    </div>" +
            "  </div>" +
            "</body></html>",
            badgeColor, badgeTextColor, statusText, headline, endpoint.getName(),
            endpoint.getUrl(), badgeTextColor, isDown ? "DOWN" : "UP (OPERATIONAL)",
            timestampStr,
            (isDown && errorMessage != null && !errorMessage.isEmpty()) 
                ? String.format("<tr><td style='padding: 8px 0; color: #94a3b8;'>Error Details:</td><td style='padding: 8px 0; color: #ef4444; font-family: monospace;'>%s</td></tr>", errorMessage)
                : "",
            frontendUrl
        );
    }

    private String buildAlertPlainText(ApiEndpoint endpoint, boolean isDown, String errorMessage, String timestampStr) {
        StringBuilder sb = new StringBuilder();
        if (isDown) {
            sb.append("Alert Notification - Vixiem Enterprise Monitoring\n\n");
            sb.append("Endpoint Name: ").append(endpoint.getName()).append("\n");
            sb.append("Target URL: ").append(endpoint.getUrl()).append("\n");
            sb.append("Status: DOWN\n");
            sb.append("Time: ").append(timestampStr).append("\n");
            sb.append("Error Details: ").append(errorMessage != null ? errorMessage : "Connection failed").append("\n\n");
            sb.append("Please check your Vixiem dashboard immediately: ").append(frontendUrl).append("/dashboard\n");
        } else {
            sb.append("Alert Notification - Vixiem Enterprise Monitoring\n\n");
            sb.append("Endpoint Name: ").append(endpoint.getName()).append("\n");
            sb.append("Target URL: ").append(endpoint.getUrl()).append("\n");
            sb.append("Status: RECOVERED (UP)\n");
            sb.append("Time: ").append(timestampStr).append("\n\n");
            sb.append("All systems have restored normal operation: ").append(frontendUrl).append("/dashboard\n");
        }
        return sb.toString();
    }

    private void sendDiscordWebhookNotification(ApiEndpoint endpoint, boolean isDown, String errorMessage) {
        String webhookUrl = endpoint.getDiscordWebhookUrl().trim();

        // SSRF & Domain Validation: Enforce HTTPS & official discord webhook hostname
        try {
            java.net.URI uri = new java.net.URI(webhookUrl);
            String host = uri.getHost();
            if (host == null || (!host.equalsIgnoreCase("discord.com") && !host.equalsIgnoreCase("discordapp.com") && !host.endsWith(".discord.com") && !host.endsWith(".discordapp.com"))) {
                log.warn("Blocked potential Discord webhook SSRF attempt for non-discord host: {}", host);
                return;
            }
            if (urlSecurityValidator != null) {
                urlSecurityValidator.validatePublicHttpUrl(webhookUrl);
            }
        } catch (Exception ex) {
            log.warn("Invalid Discord webhook URL format: {}", webhookUrl);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("username", "Vixiem Enterprise Monitor");

            Map<String, Object> embed = new HashMap<>();
            embed.put("title", isDown ? "🚨 URGENT: Endpoint DOWN" : "✅ RECOVERY: Endpoint UP");
            embed.put("color", isDown ? 15158332 : 3066993); // Red or Green

            List<Map<String, Object>> fields = new ArrayList<>();
            fields.add(createDiscordField("Endpoint Name", endpoint.getName(), true));
            fields.add(createDiscordField("Target URL", endpoint.getUrl(), true));
            fields.add(createDiscordField("Status", isDown ? "🔴 DOWN" : "🟢 UP", true));

            String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            fields.add(createDiscordField("Timestamp", timestampStr, true));

            if (isDown && errorMessage != null && !errorMessage.isEmpty()) {
                fields.add(createDiscordField("Error Details", "```" + errorMessage + "```", false));
            }

            embed.put("fields", fields);

            Map<String, String> footer = new HashMap<>();
            footer.put("text", "Vixiem Enterprise Real-Time Sentinel");
            embed.put("footer", footer);

            body.put("embeds", Collections.singletonList(embed));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(webhookUrl, entity, String.class);

            log.info("Discord Webhook alert dispatched to endpoint '{}'", endpoint.getName());
        } catch (Exception e) {
            log.error("Failed to send Discord webhook alert for endpoint '{}': {}", endpoint.getName(), e.getMessage());
        }
    }

    private Map<String, Object> createDiscordField(String name, String value, boolean inline) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }
}
