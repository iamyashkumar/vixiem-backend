package com.velorix.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.LogEntry;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
public class WeeklyReportService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${frontend.url:https://vixiem.vercel.app}")
    private String frontendUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
     * Automated weekly dispatch: Every Monday at 9:00 AM UTC
     */
    @Scheduled(cron = "${weekly.report.cron:0 0 9 * * MON}")
    public void sendWeeklyReportsToAllUsers() {
        log.info("Starting automated weekly API telemetry report dispatch for all users...");
        List<User> users = userRepository.findAll();
        int sentCount = 0;
        for (User user : users) {
            try {
                if (user.getEmail() != null && user.getEmail().contains("@")) {
                    DispatchResult result = sendWeeklyReportForUser(user, null);
                    if (result.isSuccess()) sentCount++;
                }
            } catch (Exception e) {
                log.error("Failed to send weekly report to {}: {}", user.getEmail(), e.getMessage());
            }
        }
        log.info("Automated weekly report finished. Successfully dispatched to {}/{} users.", sentCount, users.size());
    }

    /**
     * Overload for default user email
     */
    public DispatchResult sendWeeklyReportForUser(User user) {
        return sendWeeklyReportForUser(user, null);
    }

    /**
     * Send weekly report for a specific user (on-demand or automated)
     */
    /**
     * Compute and retrieve 7-day weekly report telemetry data for UI viewing & export
     */
    public Map<String, Object> getWeeklyReportDataForUser(User user) {
        List<String> userIds = new ArrayList<>();
        if (user.getId() != null) userIds.add(user.getId());
        if (user.getEmail() != null) userIds.add(user.getEmail());

        List<ApiEndpoint> endpoints = apiEndpointRepository.findByUserIdIn(userIds);
        if (endpoints.isEmpty() && user.getId() != null) {
            endpoints = apiEndpointRepository.findByUserId(user.getId());
        }

        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        Query logQuery = new Query(Criteria.where("userId").in(userIds).and("timestamp").gte(sevenDaysAgo));
        List<LogEntry> logs = mongoTemplate.find(logQuery, LogEntry.class);

        int totalEndpoints = endpoints.size();
        long totalChecks = logs.size();
        long totalErrors = logs.stream().filter(l -> "ERROR".equalsIgnoreCase(l.getLevel())).count();
        double fleetUptime = totalChecks > 0 ? ((totalChecks - totalErrors) * 100.0 / totalChecks) : 100.0;
        
        OptionalDouble avgLatOpt = logs.stream()
                .filter(l -> l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                .mapToLong(LogEntry::getResponseTimeMs)
                .average();
        double avgLatency = avgLatOpt.orElse(0.0);

        List<Map<String, Object>> endpointSummaries = new ArrayList<>();
        for (ApiEndpoint ep : endpoints) {
            long epChecks = logs.stream().filter(l -> ep.getId().equals(l.getEndpointId())).count();
            long epErrors = logs.stream().filter(l -> ep.getId().equals(l.getEndpointId()) && "ERROR".equalsIgnoreCase(l.getLevel())).count();
            double epUptime = epChecks > 0 ? ((epChecks - epErrors) * 100.0 / epChecks) : 100.0;
            OptionalDouble epLat = logs.stream()
                    .filter(l -> ep.getId().equals(l.getEndpointId()) && l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                    .mapToLong(LogEntry::getResponseTimeMs)
                    .average();

            Map<String, Object> epData = new HashMap<>();
            epData.put("id", ep.getId());
            epData.put("name", ep.getName());
            epData.put("url", ep.getUrl());
            epData.put("uptime", String.format(Locale.US, "%.2f%%", epUptime));
            epData.put("uptimeNum", Math.round(epUptime * 100.0) / 100.0);
            epData.put("latency", String.format(Locale.US, "%.1f ms", epLat.orElse(avgLatency)));
            epData.put("latencyMs", Math.round((epLat.orElse(avgLatency)) * 10.0) / 10.0);
            epData.put("isUp", ep.getLastStatus() == null || ep.getLastStatus());
            epData.put("checks", epChecks);
            epData.put("errors", epErrors);
            endpointSummaries.add(epData);
        }

        String displayName = user.getUsername() != null && !user.getUsername().trim().isEmpty() 
                ? "@" + user.getUsername() 
                : (user.getEmail() != null ? user.getEmail().split("@")[0] : "User");

        String htmlBody = buildWeeklyReportHtml(displayName, totalEndpoints, fleetUptime, avgLatency, totalChecks, totalErrors, endpointSummaries);

        Map<String, Object> result = new HashMap<>();
        result.put("totalEndpoints", totalEndpoints);
        result.put("totalChecks", totalChecks);
        result.put("totalErrors", totalErrors);
        result.put("fleetUptime", Math.round(fleetUptime * 100.0) / 100.0);
        result.put("fleetUptimeFormatted", String.format(Locale.US, "%.2f%%", fleetUptime));
        result.put("avgLatencyMs", Math.round(avgLatency * 10.0) / 10.0);
        result.put("endpoints", endpointSummaries);
        result.put("htmlReport", htmlBody);
        result.put("recipientEmail", user.getEmail());
        result.put("displayName", displayName);
        result.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss")));

        return result;
    }

    public DispatchResult sendWeeklyReportForUser(User user, String overrideEmail) {
        String targetEmail = (overrideEmail != null && !overrideEmail.trim().isEmpty()) 
                ? overrideEmail.trim() 
                : user.getEmail();

        if (targetEmail == null || !targetEmail.contains("@")) {
            log.warn("Invalid email for weekly report: {}", targetEmail);
            return new DispatchResult(false, "Invalid destination email address.", "NONE", 400);
        }

        List<String> userIds = new ArrayList<>();
        if (user.getId() != null) userIds.add(user.getId());
        if (user.getEmail() != null) userIds.add(user.getEmail());

        List<ApiEndpoint> endpoints = apiEndpointRepository.findByUserIdIn(userIds);
        if (endpoints.isEmpty() && user.getId() != null) {
            endpoints = apiEndpointRepository.findByUserId(user.getId());
        }

        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        Query logQuery = new Query(Criteria.where("userId").in(userIds).and("timestamp").gte(sevenDaysAgo));
        List<LogEntry> logs = mongoTemplate.find(logQuery, LogEntry.class);

        // Compute metrics
        int totalEndpoints = endpoints.size();
        long totalChecks = logs.size();
        long totalErrors = logs.stream().filter(l -> "ERROR".equalsIgnoreCase(l.getLevel())).count();
        double fleetUptime = totalChecks > 0 ? ((totalChecks - totalErrors) * 100.0 / totalChecks) : 100.0;
        
        OptionalDouble avgLatOpt = logs.stream()
                .filter(l -> l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                .mapToLong(LogEntry::getResponseTimeMs)
                .average();
        double avgLatency = avgLatOpt.orElse(0.0);

        // Per-endpoint metrics
        List<Map<String, Object>> endpointSummaries = new ArrayList<>();
        for (ApiEndpoint ep : endpoints) {
            long epChecks = logs.stream().filter(l -> ep.getId().equals(l.getEndpointId())).count();
            long epErrors = logs.stream().filter(l -> ep.getId().equals(l.getEndpointId()) && "ERROR".equalsIgnoreCase(l.getLevel())).count();
            double epUptime = epChecks > 0 ? ((epChecks - epErrors) * 100.0 / epChecks) : 100.0;
            OptionalDouble epLat = logs.stream()
                    .filter(l -> ep.getId().equals(l.getEndpointId()) && l.getResponseTimeMs() != null && l.getResponseTimeMs() > 0)
                    .mapToLong(LogEntry::getResponseTimeMs)
                    .average();

            Map<String, Object> epData = new HashMap<>();
            epData.put("name", ep.getName());
            epData.put("url", ep.getUrl());
            epData.put("uptime", String.format(Locale.US, "%.2f%%", epUptime));
            epData.put("latency", String.format(Locale.US, "%.1f ms", epLat.orElse(avgLatency)));
            epData.put("isUp", ep.getLastStatus() == null || ep.getLastStatus());
            endpointSummaries.add(epData);
        }

        String displayName = user.getUsername() != null && !user.getUsername().trim().isEmpty() 
                ? "@" + user.getUsername() 
                : targetEmail.split("@")[0];

        String subject = String.format(Locale.US, "[Vixiem Digest] Weekly API Telemetry: %.1f%% Fleet Uptime", fleetUptime);
        String htmlBody = buildWeeklyReportHtml(displayName, totalEndpoints, fleetUptime, avgLatency, totalChecks, totalErrors, endpointSummaries);
        String plainText = buildWeeklyReportPlainText(displayName, totalEndpoints, fleetUptime, avgLatency, totalChecks, totalErrors, endpointSummaries);

        boolean hasResendKey = resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend");
        String resendDiagnostic = null;

        if (hasResendKey) {
            try {
                String sender = resendFromEmail != null && !resendFromEmail.trim().isEmpty() ? resendFromEmail.trim() : "Vixiem <onboarding@resend.dev>";
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
                headers.put("Feedback-ID", "vixiem-weekly:render:transactional");
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
                    log.info("Weekly telemetry digest sent via Resend API to {}", targetEmail);
                    return new DispatchResult(true, "Weekly telemetry digest successfully sent to " + targetEmail, "RESEND", response.statusCode());
                } else {
                    String errorText = response.body();
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
                        if (root.has("message")) {
                            errorText = root.get("message").asText();
                        }
                    } catch (Exception ignored) {}
                    log.error("Resend API weekly report error (status {}): {}", response.statusCode(), errorText);
                    if (response.statusCode() == 403) {
                        resendDiagnostic = "Resend Sandbox Restriction: " + errorText + " Tip: To send across any email domain, verify a custom domain at resend.com/domains or send the test alert to your registered Resend email.";
                    } else if (response.statusCode() == 401) {
                        resendDiagnostic = "Resend API Key is unauthorized or invalid. Please check RESEND_API_KEY.";
                    } else {
                        resendDiagnostic = "Resend API error (" + response.statusCode() + "): " + errorText;
                    }
                }
            } catch (Exception e) {
                log.error("Resend delivery exception for weekly report to {}: {}", targetEmail, e.getMessage());
                resendDiagnostic = "Resend connection error: " + e.getMessage();
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
                log.info("Weekly telemetry digest sent via JavaMailSender to {}", targetEmail);
                return new DispatchResult(true, "Weekly telemetry digest sent via SMTP to " + targetEmail, "SMTP", 200);
            } catch (Exception e) {
                log.error("Failed to send weekly report via JavaMailSender to {}: {}", targetEmail, e.getMessage());
            }
        }

        String finalMsg;
        if (resendDiagnostic != null) {
            finalMsg = resendDiagnostic;
        } else if (!hasResendKey) {
            finalMsg = "Email service not configured. Please set RESEND_API_KEY in Render environment variables.";
        } else {
            finalMsg = "Failed to dispatch weekly report. Please check mail provider credentials.";
        }

        return new DispatchResult(false, finalMsg, hasResendKey ? "RESEND" : "NONE", 400);
    }

        private String buildWeeklyReportHtml(String username, int totalEndpoints, double uptime, double avgLatency, long totalChecks, long totalErrors, List<Map<String, Object>> endpoints) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        String period = LocalDateTime.now().minusDays(7).format(dtf) + " — " + LocalDateTime.now().format(dtf);

        StringBuilder epRows = new StringBuilder();
        if (endpoints.isEmpty()) {
            epRows.append("<tr><td colspan='4' style='padding: 16px; text-align: center; color: #64748b;'>No endpoints configured yet. Add your first endpoint in the Vixiem dashboard to activate live telemetry tracking.</td></tr>");
        } else {
            for (Map<String, Object> ep : endpoints) {
                boolean isUp = (boolean) ep.get("isUp");
                String statusBadge = isUp 
                        ? "<span style='background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; padding: 3px 8px; border-radius: 4px; font-weight: 600; font-size: 11px;'>OPERATIONAL</span>"
                        : "<span style='background: #fef2f2; color: #dc2626; border: 1px solid #fecaca; padding: 3px 8px; border-radius: 4px; font-weight: 600; font-size: 11px;'>INCIDENT</span>";

                epRows.append(String.format(
                    "<tr style='border-bottom: 1px solid #f1f5f9;'>" +
                    "  <td style='padding: 12px 14px; font-weight: 600; color: #0f172a;'>%s<br/><span style='font-size: 11px; color: #64748b; font-family: monospace;'>%s</span></td>" +
                    "  <td style='padding: 12px 14px; text-align: center;'>%s</td>" +
                    "  <td style='padding: 12px 14px; text-align: center; font-weight: 600; color: #0284c7;'>%s</td>" +
                    "  <td style='padding: 12px 14px; text-align: center; font-family: monospace; color: #475569;'>%s</td>" +
                    "</tr>",
                    ep.get("name"), ep.get("url"), statusBadge, ep.get("uptime"), ep.get("latency")
                ));
            }
        }

        String uptimeColor = uptime >= 99.0 ? "#16a34a" : (uptime >= 95.0 ? "#d97706" : "#dc2626");
        String incidentColor = totalErrors == 0 ? "#16a34a" : "#dc2626";

        return String.format(
            Locale.US,
            "<!DOCTYPE html>" +
            "<html>" +
            "<head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>" +
            "<body style='margin:0; padding:0; background-color: #f1f5f9; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif; color: #0f172a;'>" +
            "  <div style='max-width: 620px; margin: 32px auto; background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; box-shadow: 0 1px 3px rgba(0,0,0,0.06); overflow: hidden;'>" +
            "    <div style='padding: 28px 32px 20px; border-bottom: 1px solid #f1f5f9; text-align: center;'>" +
            "      <div style='font-size: 18px; font-weight: 700; color: #0f172a; letter-spacing: -0.02em;'>Vixiem</div>" +
            "      <h1 style='font-size: 20px; font-weight: 700; color: #0f172a; margin: 14px 0 4px;'>Weekly Performance Digest</h1>" +
            "      <p style='color: #64748b; font-size: 13px; margin: 0;'>Reporting Period: %s</p>" +
            "    </div>" +
            "    <div style='padding: 28px 32px;'>" +
            "      <table style='width: 100%%; margin-bottom: 24px; border-collapse: separate; border-spacing: 10px;'>" +
            "        <tr>" +
            "          <td style='background: #f8fafc; padding: 16px; border-radius: 8px; border: 1px solid #e2e8f0; text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #64748b; text-transform: uppercase; font-weight: 600;'>Fleet Uptime</div>" +
            "            <div style='font-size: 24px; font-weight: 700; color: %s; margin-top: 4px;'>%.2f%%</div>" +
            "          </td>" +
            "          <td style='background: #f8fafc; padding: 16px; border-radius: 8px; border: 1px solid #e2e8f0; text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #64748b; text-transform: uppercase; font-weight: 600;'>Avg Latency</div>" +
            "            <div style='font-size: 24px; font-weight: 700; color: #0284c7; margin-top: 4px;'>%.1f ms</div>" +
            "          </td>" +
            "        </tr>" +
            "        <tr>" +
            "          <td style='background: #f8fafc; padding: 16px; border-radius: 8px; border: 1px solid #e2e8f0; text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #64748b; text-transform: uppercase; font-weight: 600;'>Monitored Endpoints</div>" +
            "            <div style='font-size: 24px; font-weight: 700; color: #0f172a; margin-top: 4px;'>%d</div>" +
            "          </td>" +
            "          <td style='background: #f8fafc; padding: 16px; border-radius: 8px; border: 1px solid #e2e8f0; text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #64748b; text-transform: uppercase; font-weight: 600;'>Incidents (7D)</div>" +
            "            <div style='font-size: 24px; font-weight: 700; color: %s; margin-top: 4px;'>%d</div>" +
            "          </td>" +
            "        </tr>" +
            "      </table>" +
            "      <h3 style='font-size: 13px; font-weight: 700; color: #475569; text-transform: uppercase; letter-spacing: 0.05em; margin: 24px 0 10px;'>Endpoint Status Breakdown</h3>" +
            "      <div style='background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; overflow: hidden;'>" +
            "        <table style='width: 100%%; border-collapse: collapse; font-size: 12px;'>" +
            "          <thead>" +
            "            <tr style='background: #f8fafc; border-bottom: 1px solid #e2e8f0; color: #64748b; text-transform: uppercase; font-size: 10px; letter-spacing: 0.05em;'>" +
            "              <th style='padding: 10px 14px; text-align: left;'>Endpoint</th>" +
            "              <th style='padding: 10px 14px; text-align: center;'>Status</th>" +
            "              <th style='padding: 10px 14px; text-align: center;'>7D Uptime</th>" +
            "              <th style='padding: 10px 14px; text-align: center;'>Latency</th>" +
            "            </tr>" +
            "          </thead>" +
            "          <tbody>" +
            "            %s" +
            "          </tbody>" +
            "        </table>" +
            "      </div>" +
            "      <div style='margin-top: 26px; text-align: center;'>" +
            "        <a href='%s/dashboard' style='display: inline-block; background: #0284c7; color: #ffffff !important; font-weight: 600; font-size: 13px; text-decoration: none; padding: 10px 24px; border-radius: 6px; letter-spacing: 0.01em;'>" +
            "          Dashboard &rarr;" +
            "        </a>" +
            "      </div>" +
            "    </div>" +
            "    <div style='background: #f8fafc; padding: 18px 24px; border-top: 1px solid #e2e8f0; text-align: center; font-size: 11px; color: #64748b; line-height: 1.6;'>" +
            "      Vixiem Observability Platform • Weekly Telemetry Digest" +
            "    </div>" +
            "  </div>" +
            "</body>" +
            "</html>",
            period,
            uptimeColor, uptime,
            avgLatency,
            totalEndpoints,
            incidentColor, totalErrors,
            epRows.toString(),
            frontendUrl
        );
    }

private String buildWeeklyReportPlainText(String username, int totalEndpoints, double uptime, double avgLatency, long totalChecks, long totalErrors, List<Map<String, Object>> endpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("VIXIEM ENTERPRISE API TELEMETRY - WEEKLY REPORT\n");
        sb.append("================================================\n\n");
        sb.append("Hello ").append(username).append(",\n\n");
        sb.append(String.format(Locale.US, "Fleet Uptime: %.2f%%\n", uptime));
        sb.append(String.format(Locale.US, "Average Latency: %.1f ms\n", avgLatency));
        sb.append(String.format(Locale.US, "Monitored Endpoints: %d\n", totalEndpoints));
        sb.append(String.format(Locale.US, "Total Checks: %d\n", totalChecks));
        sb.append(String.format(Locale.US, "Recorded Incidents: %d\n\n", totalErrors));
        sb.append("Endpoint Breakdown:\n");
        for (Map<String, Object> ep : endpoints) {
            sb.append(String.format(Locale.US, "- %s (%s): %s uptime, %s latency\n", ep.get("name"), ep.get("url"), ep.get("uptime"), ep.get("latency")));
        }
        sb.append("\nView your complete telemetry data: ").append(frontendUrl).append("/dashboard\n");
        return sb.toString();
    }
}
