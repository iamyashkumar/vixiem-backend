package com.velorix.backend.controller;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.LogEntry;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.repository.LogRepository;
import com.velorix.backend.security.JwtUtil;
import com.velorix.backend.service.HealthCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.velorix.backend.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/endpoints")
public class ApiEndpointController {

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private HealthCheckService healthCheckService;

    @Autowired
    private com.velorix.backend.service.AuditService auditService;

    /**
     * Extract user ID from SecurityContextHolder
     */
    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return auth.getName();
    }

    /**
     * GET /api/endpoints - Get all endpoints for logged-in user
     */
    @GetMapping
    public ResponseEntity<List<ApiEndpoint>> getUserEndpoints(
            @RequestParam(required = false) String tag,
            HttpServletRequest request) {
        try {
            String userId = getUserIdFromRequest();
            List<ApiEndpoint> endpoints = apiEndpointRepository.findByUserId(userId);
            
            if (tag != null && !tag.trim().isEmpty()) {
                endpoints = endpoints.stream()
                        .filter(e -> e.getTags() != null && e.getTags().contains(tag))
                        .toList();
            }
            
            log.info("Fetched {} endpoints for user: {}", endpoints.size(), userId);
            return ResponseEntity.ok(endpoints);
        } catch (Exception e) {
            log.error("Error fetching endpoints: {}", e.getMessage());
            return ResponseEntity.status(401).body(null);
        }
    }

    /**
     * POST /api/endpoints - Create new endpoint with instant initial health check
     */
    @PostMapping
    public ResponseEntity<?> createEndpoint(@RequestBody ApiEndpoint endpoint,
                                            HttpServletRequest request) {
        try {
            String userId = getUserIdFromRequest();
            endpoint.setUserId(userId);
            endpoint.setCreatedAt(LocalDateTime.now());
            
            if (endpoint.getCheckIntervalSeconds() < 30) {
                endpoint.setCheckIntervalSeconds(60);
            }

            ApiEndpoint saved = apiEndpointRepository.save(endpoint);

            // Instant initial ping check
            try {
                long startTime = System.currentTimeMillis();
                boolean isUp = healthCheckService.checkEndpoint(saved.getUrl());
                long responseTime = System.currentTimeMillis() - startTime;

                saved.setLastStatus(isUp);
                saved.setStatusChangedAt(LocalDateTime.now());
                saved = apiEndpointRepository.save(saved);

                LogEntry logEntry = new LogEntry();
                logEntry.setUserId(userId);
                logEntry.setEndpointId(saved.getId());
                logEntry.setLevel(isUp ? "INFO" : "ERROR");
                logEntry.setSource(saved.getName());
                logEntry.setMessage(isUp 
                        ? "Endpoint is UP. Response time: " + responseTime + "ms" 
                        : "HTTP Check Failed for " + saved.getUrl() + " (Returned status code error or 4xx/5xx failure)");
                logEntry.setResponseTimeMs(isUp ? responseTime : null);
                logEntry.setTimestamp(LocalDateTime.now());
                logRepository.save(logEntry);
            } catch (Exception ex) {
                log.warn("Immediate health check failed for {}: {}", saved.getUrl(), ex.getMessage());
            }

            auditService.logEvent(userId, "ENDPOINT_CREATED", Map.of("endpointId", saved.getId(), "name", saved.getName()));
            
            log.info("Endpoint created: {} for user: {}", endpoint.getName(), userId);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Error creating endpoint: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/endpoints/{id} - Update endpoint
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateEndpoint(@PathVariable String id,
                                            @RequestBody ApiEndpoint updated,
                                            HttpServletRequest request) {
        try {
            String userId = getUserIdFromRequest();

            Optional<ApiEndpoint> existingOpt = apiEndpointRepository.findByIdAndUserId(id, userId);
            if (existingOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "Endpoint not found or not authorized"));
            }

            ApiEndpoint existing = existingOpt.get();

            existing.setName(updated.getName());
            existing.setUrl(updated.getUrl());
            existing.setActive(updated.isActive());
            existing.setAlertsEnabled(updated.isAlertsEnabled());
            existing.setTags(updated.getTags());
            
            if (updated.getCheckIntervalSeconds() >= 30) {
                existing.setCheckIntervalSeconds(updated.getCheckIntervalSeconds());
            }

            // Run instant re-check
            try {
                long startTime = System.currentTimeMillis();
                boolean isUp = healthCheckService.checkEndpoint(existing.getUrl());
                long responseTime = System.currentTimeMillis() - startTime;

                existing.setLastStatus(isUp);
                existing.setStatusChangedAt(LocalDateTime.now());
            } catch (Exception ex) {
                log.warn("Immediate health check failed on update: {}", ex.getMessage());
            }

            ApiEndpoint saved = apiEndpointRepository.save(existing);
            
            auditService.logEvent(userId, "ENDPOINT_UPDATED", Map.of("endpointId", saved.getId(), "name", saved.getName()));
            
            log.info("Endpoint updated: {} by user: {}", id, userId);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Error updating endpoint: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/endpoints/{id} - Delete endpoint
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEndpoint(@PathVariable String id,
                                            HttpServletRequest request) {
        try {
            String userId = getUserIdFromRequest();

            Optional<ApiEndpoint> existingOpt = apiEndpointRepository.findByIdAndUserId(id, userId);
            if (existingOpt.isEmpty()) {
                log.warn("Delete attempt: Endpoint {} not found or not authorized", id);
                return ResponseEntity.status(404).body(Map.of("error", "Endpoint not found"));
            }

            ApiEndpoint existing = existingOpt.get();

            apiEndpointRepository.deleteById(id);
            
            auditService.logEvent(userId, "ENDPOINT_DELETED", Map.of("endpointId", id, "name", existing.getName()));
            
            log.info("Endpoint deleted: {} ({}) by user: {}", id, existing.getName(), userId);

            return ResponseEntity.ok(Map.of(
                    "message", "Endpoint deleted successfully",
                    "deletedId", id,
                    "deletedName", existing.getName()
            ));
        } catch (Exception e) {
            log.error("Error deleting endpoint: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}