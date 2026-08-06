package com.velorix.backend.service;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
    private HealthCheckService healthCheckService;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    public void sendDowntimeAlert(ApiEndpoint endpoint, boolean isDown, String errorMessage) {
        log.info("Triggering alert notification for endpoint '{}' (isDown={})", endpoint.getName(), isDown);

        // 1. Send Email Alert
        if (endpoint.isAlertsEnabled()) {
            sendEmailNotification(endpoint, isDown, errorMessage);
        }

        // 2. Send Discord Webhook Alert
        if (endpoint.getDiscordWebhookUrl() != null && !endpoint.getDiscordWebhookUrl().trim().isEmpty()) {
            sendDiscordWebhookNotification(endpoint, isDown, errorMessage);
        }
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
                if (userOpt.isPresent()) {
                    targetEmail = userOpt.get().getEmail();
                }
            }
        }

        if (targetEmail == null || !targetEmail.contains("@")) {
            log.warn("No valid email destination found for endpoint '{}'", endpoint.getName());
            return;
        }

        if (mailSender == null) {
            log.warn("JavaMailSender not configured. Skipping email dispatch to {}", targetEmail);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(targetEmail);
            String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            if (isDown) {
                message.setSubject("🚨 URGENT: " + endpoint.getName() + " is DOWN");
                message.setText(String.format(
                        "Alert Notification - Vixiem Enterprise Monitoring\n\n" +
                        "Endpoint Name: %s\n" +
                        "Target URL: %s\n" +
                        "Status: DOWN\n" +
                        "Time: %s\n" +
                        "Error Details: %s\n\n" +
                        "Please check your Vixiem dashboard immediately.",
                        endpoint.getName(), endpoint.getUrl(), timestampStr, (errorMessage != null ? errorMessage : "Connection failed / non-2xx response")
                ));
            } else {
                message.setSubject("✅ RECOVERY: " + endpoint.getName() + " is UP");
                message.setText(String.format(
                        "Alert Notification - Vixiem Enterprise Monitoring\n\n" +
                        "Endpoint Name: %s\n" +
                        "Target URL: %s\n" +
                        "Status: RECOVERED (UP)\n" +
                        "Time: %s\n\n" +
                        "All systems have restored normal operation.",
                        endpoint.getName(), endpoint.getUrl(), timestampStr
                ));
            }

            mailSender.send(message);
            log.info("Email alert sent successfully to {} for endpoint '{}'", targetEmail, endpoint.getName());
        } catch (Exception e) {
            log.error("Failed to send email alert for endpoint '{}': {}", endpoint.getName(), e.getMessage());
        }
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
            if (healthCheckService != null) {
                healthCheckService.validatePublicHttpUrl(webhookUrl);
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
