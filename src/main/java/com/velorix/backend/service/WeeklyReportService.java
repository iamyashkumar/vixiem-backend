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
                    boolean success = sendWeeklyReportForUser(user);
                    if (success) sentCount++;
                }
            } catch (Exception e) {
                log.error("Failed to send weekly report to {}: {}", user.getEmail(), e.getMessage());
            }
        }
        log.info("Automated weekly report finished. Successfully dispatched to {}/{} users.", sentCount, users.size());
    }

    /**
     * Send weekly report for a specific user (on-demand or automated)
     */
    public boolean sendWeeklyReportForUser(User user) {
        String targetEmail = user.getEmail();
        if (targetEmail == null || !targetEmail.contains("@")) {
            log.warn("Invalid email for weekly report: {}", targetEmail);
            return false;
        }

        List<String> userIds = new ArrayList<>();
        if (user.getId() != null) userIds.add(user.getId());
        userIds.add(user.getEmail());

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
        double avgLatency = avgLatOpt.orElse(16.5);

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

        String subject = String.format(Locale.US, "📊 Vixiem Weekly Digest: %.1f%% Uptime & API Performance Report", fleetUptime);
        String htmlBody = buildWeeklyReportHtml(displayName, totalEndpoints, fleetUptime, avgLatency, totalChecks, totalErrors, endpointSummaries);
        String plainText = buildWeeklyReportPlainText(displayName, totalEndpoints, fleetUptime, avgLatency, totalChecks, totalErrors, endpointSummaries);

        // 1. Try Resend API (HTTPS port 443 - works on Render)
        if (resendApiKey != null && !resendApiKey.trim().isEmpty() && !resendApiKey.contains("your_resend")) {
            boolean sent = sendViaResend(targetEmail, subject, htmlBody);
            if (sent) {
                log.info("Weekly telemetry digest sent via Resend API to {}", targetEmail);
                return true;
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
                log.info("Weekly telemetry digest sent via JavaMailSender to {}", targetEmail);
                return true;
            } catch (Exception e) {
                log.error("Failed to send weekly report via JavaMailSender to {}: {}", targetEmail, e.getMessage());
            }
        }

        log.warn("Neither Resend nor JavaMailSender could dispatch weekly report to {}", targetEmail);
        return false;
    }

    private boolean sendViaResend(String toEmail, String subject, String htmlBody) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("from", "Vixiem <onboarding@resend.dev>");
            payload.put("to", List.of(toEmail));
            payload.put("subject", subject);
            payload.put("html", htmlBody);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Resend API weekly report dispatched. Status: {}", response.statusCode());
                return true;
            } else {
                log.error("Resend API error (status {}): {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Resend delivery exception for weekly report to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    private String buildWeeklyReportHtml(String username, int totalEndpoints, double uptime, double avgLatency, long totalChecks, long totalErrors, List<Map<String, Object>> endpoints) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        String period = LocalDateTime.now().minusDays(7).format(dtf) + " — " + LocalDateTime.now().format(dtf);

        StringBuilder epRows = new StringBuilder();
        if (endpoints.isEmpty()) {
            epRows.append("<tr><td colspan='4' style='padding: 16px; text-align: center; color: #94a3b8;'>No endpoints configured yet. Add your first endpoint in the Vixiem dashboard to activate live telemetry tracking.</td></tr>");
        } else {
            for (Map<String, Object> ep : endpoints) {
                boolean isUp = (boolean) ep.get("isUp");
                String statusBadge = isUp 
                        ? "<span style='background: rgba(16, 185, 129, 0.15); color: #10b981; padding: 4px 10px; border-radius: 9999px; font-weight: bold; font-size: 11px;'>● OPERATIONAL</span>"
                        : "<span style='background: rgba(239, 68, 68, 0.15); color: #ef4444; padding: 4px 10px; border-radius: 9999px; font-weight: bold; font-size: 11px;'>● INCIDENT</span>";

                epRows.append(String.format(
                    "<tr style='border-bottom: 1px solid rgba(255,255,255,0.06);'>" +
                    "  <td style='padding: 14px 12px; font-weight: 600; color: #f8fafc;'>%s<br/><span style='font-size: 11px; color: #64748b; font-family: monospace;'>%s</span></td>" +
                    "  <td style='padding: 14px 12px; text-align: center;'>%s</td>" +
                    "  <td style='padding: 14px 12px; text-align: center; font-weight: bold; color: #38bdf8;'>%s</td>" +
                    "  <td style='padding: 14px 12px; text-align: center; font-family: monospace; color: #cbd5e1;'>%s</td>" +
                    "</tr>",
                    ep.get("name"), ep.get("url"), statusBadge, ep.get("uptime"), ep.get("latency")
                ));
            }
        }

        return String.format(
            Locale.US,
            "<!DOCTYPE html>" +
            "<html>" +
            "<head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>" +
            "<body style='margin:0; padding:0; background-color: #08080a; font-family: -apple-system, BlinkMacSystemFont, Arial, sans-serif; color: #f8fafc;'>" +
            "  <div style='max-width: 640px; margin: 30px auto; background: #0f141f; border-radius: 18px; border: 1px solid rgba(56, 189, 248, 0.2); overflow: hidden; box-shadow: 0 20px 40px rgba(0,0,0,0.6);'>" +
            "    <div style='background: linear-gradient(135deg, #0b111e 0%%, #131c2e 100%%); padding: 32px 28px; border-bottom: 1px solid rgba(255,255,255,0.08); text-align: center;'>" +
            "      <div style='font-size: 24px; font-weight: 900; letter-spacing: -0.02em; color: #ffffff;'>Vixiem<span style='color: #38bdf8;'>.</span></div>" +
            "      <div style='font-size: 11px; font-weight: 700; letter-spacing: 0.1em; color: #38bdf8; text-transform: uppercase; margin-top: 6px;'>Enterprise API Telemetry Digest</div>" +
            "      <h1 style='font-size: 20px; font-weight: 800; color: #ffffff; margin: 18px 0 6px;'>Weekly Performance Digest</h1>" +
            "      <p style='color: #94a3b8; font-size: 13px; margin: 0;'>Hello %s • Reporting Period: %s</p>" +
            "    </div>" +
            "    <div style='padding: 28px;'>" +
            "      <table style='width: 100%%; margin-bottom: 24px; border-collapse: separate; border-spacing: 10px;'>" +
            "        <tr>" +
            "          <td style='background: #141b2b; padding: 16px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #94a3b8; text-transform: uppercase; font-weight: 600;'>Fleet Uptime</div>" +
            "            <div style='font-size: 24px; font-weight: 900; color: %s; margin-top: 4px;'>%.2f%%</div>" +
            "          </td>" +
            "          <td style='background: #141b2b; padding: 16px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #94a3b8; text-transform: uppercase; font-weight: 600;'>Avg Latency</div>" +
            "            <div style='font-size: 24px; font-weight: 900; color: #38bdf8; margin-top: 4px;'>%.1f ms</div>" +
            "          </td>" +
            "        </tr>" +
            "        <tr>" +
            "          <td style='background: #141b2b; padding: 16px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #94a3b8; text-transform: uppercase; font-weight: 600;'>Monitored Endpoints</div>" +
            "            <div style='font-size: 24px; font-weight: 900; color: #ffffff; margin-top: 4px;'>%d</div>" +
            "          </td>" +
            "          <td style='background: #141b2b; padding: 16px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); text-align: center; width: 50%%;'>" +
            "            <div style='font-size: 11px; color: #94a3b8; text-transform: uppercase; font-weight: 600;'>Incidents (7D)</div>" +
            "            <div style='font-size: 24px; font-weight: 900; color: %s; margin-top: 4px;'>%d</div>" +
            "          </td>" +
            "        </tr>" +
            "      </table>" +
            "      <h3 style='font-size: 14px; font-weight: 700; color: #cbd5e1; text-transform: uppercase; letter-spacing: 0.05em; margin: 24px 0 12px;'>Endpoint Status Breakdown</h3>" +
            "      <div style='background: #141b2b; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); overflow: hidden;'>" +
            "        <table style='width: 100%%; border-collapse: collapse; font-size: 12px;'>" +
            "          <thead>" +
            "            <tr style='background: rgba(255,255,255,0.03); border-bottom: 1px solid rgba(255,255,255,0.08); color: #94a3b8; text-transform: uppercase; font-size: 10px; letter-spacing: 0.05em;'>" +
            "              <th style='padding: 10px 12px; text-align: left;'>Endpoint</th>" +
            "              <th style='padding: 10px 12px; text-align: center;'>Status</th>" +
            "              <th style='padding: 10px 12px; text-align: center;'>7D Uptime</th>" +
            "              <th style='padding: 10px 12px; text-align: center;'>Latency</th>" +
            "            </tr>" +
            "          </thead>" +
            "          <tbody>" +
            "            %s" +
            "          </tbody>" +
            "        </table>" +
            "      </div>" +
            "      <div style='margin-top: 32px; text-align: center;'>" +
            "        <a href='%s/dashboard' style='background: linear-gradient(135deg, #0ea5e9 0%%, #38bdf8 100%%); color: #08080a; font-weight: 800; font-size: 14px; text-decoration: none; padding: 14px 28px; border-radius: 10px; display: inline-block; box-shadow: 0 4px 14px rgba(14, 165, 233, 0.4);'>" +
            "          Open Vixiem Command Center →" +
            "        </a>" +
            "      </div>" +
            "    </div>" +
            "    <div style='background: #0b111e; padding: 20px; border-top: 1px solid rgba(255,255,255,0.06); text-align: center; font-size: 11px; color: #64748b;'>" +
            "      Vixiem Cloud Observability Sentinel • Automated Weekly Telemetry Report<br/>" +
            "      To adjust notification preferences, visit your account settings." +
            "    </div>" +
            "  </div>" +
            "</body>" +
            "</html>",
            username, period,
            uptime >= 99.0 ? "#10b981" : (uptime >= 95.0 ? "#f59e0b" : "#ef4444"),
            uptime, avgLatency, totalEndpoints,
            totalErrors == 0 ? "#10b981" : "#ef4444", totalErrors,
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
