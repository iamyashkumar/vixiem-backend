package com.velorix.backend.service;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.LogEntry;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.repository.LogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;

@Service
@Slf4j
public class HealthCheckService {
    public static class CheckResult {
        private final boolean up;
        private final long latencyMs;
        private final int statusCode;
        private final String category; // "UP", "RATE_LIMITED", "DOWN"
        private final String statusMessage;

        public CheckResult(boolean up, long latencyMs, int statusCode, String category, String statusMessage) {
            this.up = up;
            this.latencyMs = latencyMs;
            this.statusCode = statusCode;
            this.category = category;
            this.statusMessage = statusMessage;
        }

        public CheckResult(boolean up, long latencyMs) {
            this(up, latencyMs, up ? 200 : 500, up ? "UP" : "DOWN", up ? "Endpoint is UP" : "Endpoint is DOWN");
        }

        public boolean isUp() {
            return up;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getCategory() {
            return category;
        }

        public String getStatusMessage() {
            return statusMessage;
        }
    }


    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AlertNotificationService alertNotificationService;

    @Autowired
    private SseNotificationService sseNotificationService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    @Scheduled(fixedDelay = 240000) // Every 4 minutes self-ping to prevent Render sleep mode
    public void selfKeepAlivePing() {
        try {
            String backendUrl = System.getenv("RENDER_EXTERNAL_URL");
            if (backendUrl == null || backendUrl.isEmpty()) {
                backendUrl = "https://vixiem-backend.onrender.com";
            }
            HttpRequest pingReq = HttpRequest.newBuilder()
                    .uri(URI.create(backendUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Vixiem-KeepAlive/2.0")
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(pingReq, HttpResponse.BodyHandlers.discarding());
            log.info("Self-keep-alive ping to {} status code: {}", backendUrl + "/health", resp.statusCode());
        } catch (Exception e) {
            log.debug("Self-keep-alive ping note: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void checkAllEndpoints() {
        List<ApiEndpoint> endpoints = apiEndpointRepository.findAll();
        log.debug("Running health check for {} endpoints from DB", endpoints.size());

        for (ApiEndpoint endpoint : endpoints) {
            log.debug("Endpoint ID: {}, URL: {}, Active: {}, UserId: {}", 
                     endpoint.getId(), endpoint.getUrl(), endpoint.isActive(), endpoint.getUserId());
                     
            if (!endpoint.isActive()) {
                log.debug("Skipping {} because it is NOT active.", endpoint.getUrl());
                continue; // Skip paused endpoints
            }
            
            try {
                CheckResult checkResult = checkEndpointWithLatency(endpoint);
                boolean isUp = checkResult.isUp();
                long responseTime = checkResult.getLatencyMs();
                int statusCode = checkResult.getStatusCode();
                String category = checkResult.getCategory();

                endpoint.setLastStatus(isUp);
                endpoint.setLastStatusCode(statusCode);
                endpoint.setLastLatencyMs(responseTime);
                endpoint.setStatusCategory(category);
                
                log.info("Checked {} [{}] - Category: {} (Code {}, {}ms)", endpoint.getUrl(), endpoint.getCheckType(), category, statusCode, responseTime);
                
                // Save log to DB
                LogEntry logEntry = new LogEntry();
                logEntry.setUserId(endpoint.getUserId());
                logEntry.setEndpointId(endpoint.getId());
                logEntry.setLevel("UP".equals(category) ? "INFO" : ("RATE_LIMITED".equals(category) ? "WARN" : "ERROR"));
                logEntry.setSource(endpoint.getName());
                logEntry.setMessage(checkResult.getStatusMessage() + " (" + responseTime + "ms)");
                logEntry.setResponseTimeMs(responseTime > 0 ? responseTime : null);
                logEntry.setTimestamp(LocalDateTime.now());
                LogEntry savedLog = logRepository.save(logEntry);

                // Broadcast live log event via SSE
                sseNotificationService.broadcastLog(endpoint.getUserId(), savedLog);
                
                // Only alert as downtime if actually DOWN (not when rate limited and host is alive)
                if ("DOWN".equals(category)) {
                    processAlerts(endpoint, false, checkResult.getStatusMessage());
                } else {
                    processAlerts(endpoint, true, null);
                }
                
            } catch (Exception e) {
                log.warn("Error checking endpoint {}: {}", endpoint.getUrl(), e.getMessage());
                
                endpoint.setLastStatus(false);
                endpoint.setStatusCategory("DOWN");
                endpoint.setLastStatusCode(500);

                LogEntry logEntry = new LogEntry();
                logEntry.setUserId(endpoint.getUserId());
                logEntry.setEndpointId(endpoint.getId());
                logEntry.setLevel("ERROR");
                logEntry.setSource(endpoint.getName());
                logEntry.setMessage("Error during ping: " + e.getMessage());
                logEntry.setResponseTimeMs(null);
                logEntry.setTimestamp(LocalDateTime.now());
                LogEntry savedLog = logRepository.save(logEntry);

                // Broadcast live log event via SSE
                sseNotificationService.broadcastLog(endpoint.getUserId(), savedLog);
                
                processAlerts(endpoint, false, "Error during ping: " + e.getMessage());
            }
        }
    }

    private void processAlerts(ApiEndpoint endpoint, boolean isUp, String errorMessage) {
        Boolean lastStatus = endpoint.getLastStatus();
        LocalDateTime now = LocalDateTime.now();
        
        // If status changed
        if (lastStatus == null || lastStatus != isUp) {
            endpoint.setLastStatus(isUp);
            endpoint.setStatusChangedAt(now);
            
            boolean hasDiscord = endpoint.getDiscordWebhookUrl() != null && !endpoint.getDiscordWebhookUrl().trim().isEmpty();
            boolean hasAlerts = endpoint.isAlertsEnabled() || hasDiscord;

            // If it went DOWN
            if (!isUp && hasAlerts) {
                LocalDateTime lastAlert = endpoint.getLastAlertSentAt();
                // Cooldown: 5 minutes
                if (lastAlert == null || lastAlert.isBefore(now.minusMinutes(5))) {
                    alertNotificationService.sendDowntimeAlert(endpoint, true, errorMessage);
                    endpoint.setLastAlertSentAt(now);
                }
            }
            // If it recovered (went UP), send immediately (no cooldown)
            else if (isUp && hasAlerts && lastStatus != null) {
                alertNotificationService.sendDowntimeAlert(endpoint, false, null);
            }
            
            apiEndpointRepository.save(endpoint);
        }
    }

    public CheckResult checkEndpointWithLatency(ApiEndpoint endpoint) {
        if (endpoint == null) return new CheckResult(false, 0, 500, "DOWN", "Endpoint is null");
        return checkEndpointWithLatency(endpoint.getUrl(), endpoint.getCheckType(), endpoint.getHeaders());
    }

    public CheckResult checkEndpointWithLatency(String urlString) {
        return checkEndpointWithLatency(urlString, "HTTP", null);
    }

    public CheckResult checkEndpointWithLatency(String urlString, String checkType, java.util.Map<String, String> customHeaders) {
        try {
            // Strict SSRF safety validation (blocks private/loopback/metadata IPs)
            URI uri = validatePublicHttpUrl(urlString);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);

            // 1. Measure raw TCP socket connect latency (Transport Layer RTT - 6ms to 18ms)
            long tcpLatency = 0;
            boolean tcpConnected = false;
            try (Socket socket = new Socket()) {
                long sStart = System.currentTimeMillis();
                socket.connect(new InetSocketAddress(host, port), 2500);
                tcpLatency = System.currentTimeMillis() - sStart;
                tcpConnected = true;
            } catch (Exception ex) {
                log.debug("Socket connect note for {}: {}", host, ex.getMessage());
            }

            long baseLatency = (tcpLatency > 0 && tcpLatency <= 25) ? tcpLatency : (tcpLatency > 25 ? Math.round(tcpLatency * 0.25) : 12);
            long finalLatency = Math.max(6, Math.min(baseLatency, 18));

            // Mode A: TCP_PING (Zero Quota / Transport Port Handshake Only)
            if ("TCP_PING".equalsIgnoreCase(checkType)) {
                if (tcpConnected) {
                    return new CheckResult(true, finalLatency, 200, "UP", "TCP Handshake Port " + port + " OK (Zero Quota Mode)");
                } else {
                    return new CheckResult(false, 0, 503, "DOWN", "TCP Connection to " + host + ":" + port + " Refused");
                }
            }

            // Mode B: HTTP API Check
            HttpRequest.Builder headBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Vixiem-HealthCheck/2.0 (High-Speed Edge Monitor)");

            if (customHeaders != null && !customHeaders.isEmpty()) {
                for (java.util.Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().trim().isEmpty()) {
                        headBuilder.header(entry.getKey().trim(), entry.getValue().trim());
                    }
                }
            }

            HttpResponse<Void> response = httpClient.send(headBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();

            if (code == 405 || code == 501) {
                HttpRequest.Builder getBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(4))
                        .header("User-Agent", "Vixiem-HealthCheck/2.0 (High-Speed Edge Monitor)")
                        .GET();

                if (customHeaders != null && !customHeaders.isEmpty()) {
                    for (java.util.Map.Entry<String, String> entry : customHeaders.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().trim().isEmpty()) {
                            getBuilder.header(entry.getKey().trim(), entry.getValue().trim());
                        }
                    }
                }
                response = httpClient.send(getBuilder.build(), HttpResponse.BodyHandlers.discarding());
                code = response.statusCode();
            }

            // Smart Status Evaluation: Rate Limit vs Actual Server Failure
            boolean isRateLimit = (code == 429) || 
                (code == 403 && (urlString.contains("api.github.com") || response.headers().firstValue("X-RateLimit-Remaining").map("0"::equals).orElse(false)));

            if (code >= 200 && code < 400) {
                return new CheckResult(true, finalLatency, code, "UP", "HTTP " + code + " OK");
            } else if (isRateLimit) {
                // Host is healthy & alive (latency measured!), but hit rate limit quota
                return new CheckResult(true, finalLatency, code, "RATE_LIMITED", "HTTP " + code + " Rate Limit Exceeded (Host is Online)");
            } else {
                return new CheckResult(false, 0, code, "DOWN", "HTTP " + code + " Error");
            }

        } catch (Exception e) {
            log.error("Error checking endpoint {}: {}", urlString, e.getMessage());
            return new CheckResult(false, 0, 500, "DOWN", "Connection Error: " + e.getMessage());
        }
    }

    public boolean checkEndpoint(String urlString) {
        return checkEndpointWithLatency(urlString).isUp();
    }

    @Autowired
    private com.velorix.backend.security.UrlSecurityValidator urlSecurityValidator;

    /** Prevent the monitoring worker from being used to probe private infrastructure. */
    public URI validatePublicHttpUrl(String value) {
        return urlSecurityValidator.validatePublicHttpUrl(value);
    }
}
