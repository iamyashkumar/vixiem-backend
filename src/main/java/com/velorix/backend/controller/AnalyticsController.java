package com.velorix.backend.controller;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.service.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;
    
    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return auth.getName(); // Returns the email mapped as userId
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics(@RequestParam(required = false) String endpointId,
                                        @RequestParam(defaultValue = "7") int days) {
        try {
            String userId = getUserIdFromRequest();
            
            // Strictly verify ownership if endpointId is provided
            if (endpointId != null && !endpointId.isEmpty()) {
                Optional<ApiEndpoint> endpointOpt = apiEndpointRepository.findByIdAndUserId(endpointId, userId);
                if (endpointOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("error", "Endpoint not found or not authorized"));
                }
            }
            
            List<Map> metrics = analyticsService.getDailyMetrics(userId, endpointId, days);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error fetching analytics metrics: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        try {
            String userId = getUserIdFromRequest();
            Map<String, Object> summary = analyticsService.getSummary(userId);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error fetching analytics summary: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}