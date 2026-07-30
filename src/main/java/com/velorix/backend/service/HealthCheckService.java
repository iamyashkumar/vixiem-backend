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

    @Scheduled(fixedDelay = 60000) // Every 60 seconds
    public void checkAllEndpoints() {
        List<ApiEndpoint> endpoints = apiEndpointRepository.findAll();
        log.info("Running health check for {} endpoints from DB", endpoints.size());

        for (ApiEndpoint endpoint : endpoints) {
            log.info("DEBUG: Found Endpoint in DB -> ID: {}, URL: {}, Active: {}, UserId: {}", 
                     endpoint.getId(), endpoint.getUrl(), endpoint.isActive(), endpoint.getUserId());
                     
            if (!endpoint.isActive()) {
                log.info("DEBUG: Skipping {} because it is NOT active.", endpoint.getUrl());
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
                logRepository.save(logEntry);
                
                processAlerts(endpoint, isUp);
                
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
                logRepository.save(logEntry);
                
                processAlerts(endpoint, false);
            }
        }
    }

    private void processAlerts(ApiEndpoint endpoint, boolean isUp) {
        Boolean lastStatus = endpoint.getLastStatus();
        LocalDateTime now = LocalDateTime.now();
        
        // If status changed
        if (lastStatus == null || lastStatus != isUp) {
            endpoint.setLastStatus(isUp);
            endpoint.setStatusChangedAt(now);
            
            // If it went DOWN and alerts are enabled
            if (!isUp && endpoint.isAlertsEnabled()) {
                LocalDateTime lastAlert = endpoint.getLastAlertSentAt();
                // Cooldown: 5 minutes
                if (lastAlert == null || lastAlert.isBefore(now.minusMinutes(5))) {
                    sendEmail(endpoint, true);
                    endpoint.setLastAlertSentAt(now);
                }
            }
            // If it recovered (went UP) and alerts are enabled, send immediately (no cooldown)
            else if (isUp && endpoint.isAlertsEnabled() && lastStatus != null) {
                sendEmail(endpoint, false);
                // We don't update lastAlertSentAt for recovery to not block subsequent DOWN alerts
            }
            
            apiEndpointRepository.save(endpoint);
        }
    }

    private void sendEmail(ApiEndpoint endpoint, boolean isDown) {
        String email = endpoint.getUserId(); // Assume it's the email
        if (!email.contains("@")) {
            // It might be an ID (from old seed data), fetch user
            Optional<User> userOpt = userRepository.findById(email);
            if (userOpt.isPresent()) {
                email = userOpt.get().getEmail();
            }
        }
        if (email.contains("@")) {
            emailService.sendAlertEmail(email, endpoint.getName(), endpoint.getUrl(), isDown);
        }
    }

    public boolean checkEndpoint(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.connect();

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            log.error("Error checking endpoint: {}", e.getMessage());
            return false;
        }
    }
}