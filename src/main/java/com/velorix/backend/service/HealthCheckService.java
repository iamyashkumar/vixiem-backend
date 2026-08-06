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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;

@Service
@Slf4j
public class HealthCheckService {

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
                long startTime = System.currentTimeMillis();
                boolean isUp = checkEndpoint(endpoint.getUrl());
                long responseTime = System.currentTimeMillis() - startTime;
                
                log.info("Checked {} - UP: {} ({}ms)", endpoint.getUrl(), isUp, responseTime);
                
                // Save log to DB
                LogEntry logEntry = new LogEntry();
                logEntry.setUserId(endpoint.getUserId());
                logEntry.setEndpointId(endpoint.getId());
                logEntry.setLevel(isUp ? "INFO" : "ERROR");
                logEntry.setSource(endpoint.getName());
                logEntry.setMessage(isUp ? "Endpoint is UP. Response time: " + responseTime + "ms" : "Endpoint is DOWN or unreachable.");
                logEntry.setResponseTimeMs(isUp ? responseTime : null);
                logEntry.setTimestamp(LocalDateTime.now());
                LogEntry savedLog = logRepository.save(logEntry);

                // Broadcast live log event via SSE
                sseNotificationService.broadcastLog(endpoint.getUserId(), savedLog);
                
                processAlerts(endpoint, isUp, isUp ? null : "HTTP health check failed / non-2xx response");
                
            } catch (Exception e) {
                log.warn("Error checking endpoint {}: {}", endpoint.getUrl(), e.getMessage());
                
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

    public boolean checkEndpoint(String urlString) {
        try {
            URI uri = validatePublicHttpUrl(urlString);
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.connect();

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            log.error("Error checking endpoint: {}", e.getMessage());
            return false;
        }
    }

    /** Prevent the monitoring worker from being used to probe private infrastructure. */
    public URI validatePublicHttpUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Only absolute HTTP(S) URLs without credentials are allowed");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivateAddress(address)) {
                    throw new IllegalArgumentException("Private, loopback, and link-local addresses cannot be monitored");
                }
            }
            return uri;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The endpoint host could not be resolved");
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint URL");
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean carrierGradeNat = bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || carrierGradeNat;
    }
}
