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
    private com.velorix.backend.repository.UserRepository userRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private com.velorix.backend.service.WeeklyReportService weeklyReportService;

    @Autowired
    private com.velorix.backend.service.AlertNotificationService alertNotificationService;

    private List<String> getUserIdsFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        String email = auth.getName();
        Optional<com.velorix.backend.model.User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            return List.of(userOpt.get().getId(), email);
        }
        return List.of(email);
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics(@RequestParam(required = false) String endpointId,
                                        @RequestParam(defaultValue = "7") int days) {
        try {
            List<String> userIds = getUserIdsFromRequest();
            
            // Strictly verify ownership if endpointId is provided
            if (endpointId != null && !endpointId.isEmpty()) {
                Optional<ApiEndpoint> endpointOpt = apiEndpointRepository.findByIdAndUserIdIn(endpointId, userIds);
                if (endpointOpt.isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("error", "Endpoint not found or not authorized"));
                }
            }
            
            List<Map> metrics = analyticsService.getDailyMetrics(userIds, endpointId, days);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error fetching analytics metrics: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        try {
            List<String> userIds = getUserIdsFromRequest();
            Map<String, Object> summary = analyticsService.getSummary(userIds);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("Error fetching analytics summary: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/send-weekly-report")
    public ResponseEntity<?> sendWeeklyReport() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            String email = auth.getName();
            Optional<com.velorix.backend.model.User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "User not found"));
            }

            com.velorix.backend.model.User user = userOpt.get();
            boolean dispatched = weeklyReportService.sendWeeklyReportForUser(user);

            if (dispatched) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Weekly telemetry digest successfully sent to " + email
                ));
            } else {
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Failed to dispatch weekly report. Please check mail provider credentials."
                ));
            }
        } catch (Exception e) {
            log.error("Error triggering weekly report: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/test-alert")
    public ResponseEntity<?> sendTestAlert() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            String email = auth.getName();
            Optional<com.velorix.backend.model.User> userOpt = userRepository.findByEmail(email);
            String username = userOpt.map(com.velorix.backend.model.User::getUsername).orElse(email.split("@")[0]);

            boolean dispatched = alertNotificationService.sendTestAlert(email, username);

            if (dispatched) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Test alert email successfully dispatched to " + email
                ));
            } else {
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Failed to send test alert. Please verify mail provider configuration."
                ));
            }
        } catch (Exception e) {
            log.error("Error triggering test alert: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
