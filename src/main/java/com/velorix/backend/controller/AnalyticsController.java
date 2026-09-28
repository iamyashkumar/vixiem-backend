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
    public ResponseEntity<?> sendWeeklyReport(@RequestBody(required = false) Map<String, String> body) {
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
            String customEmail = (body != null && body.get("email") != null && !body.get("email").trim().isEmpty())
                    ? body.get("email").trim()
                    : null;

            com.velorix.backend.service.WeeklyReportService.DispatchResult result = weeklyReportService.sendWeeklyReportForUser(user, customEmail);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.getMessage(),
                    "provider", result.getProvider()
                ));
            } else {
                return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "message", result.getMessage(),
                    "provider", result.getProvider()
                ));
            }
        } catch (Exception e) {
            log.error("Error triggering weekly report: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", e.getMessage() != null ? e.getMessage() : "Error triggering weekly report"
            ));
        }
    }

    @PostMapping("/test-alert")
    public ResponseEntity<?> sendTestAlert(@RequestBody(required = false) Map<String, String> body) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            String authEmail = auth.getName();
            String targetEmail = (body != null && body.get("email") != null && !body.get("email").trim().isEmpty())
                    ? body.get("email").trim()
                    : authEmail;

            Optional<com.velorix.backend.model.User> userOpt = userRepository.findByEmail(authEmail);
            String username = userOpt.map(com.velorix.backend.model.User::getUsername).orElse(targetEmail.split("@")[0]);

            com.velorix.backend.service.AlertNotificationService.DispatchResult result = alertNotificationService.sendTestAlert(targetEmail, username);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", result.getMessage(),
                    "provider", result.getProvider()
                ));
            } else {
                return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "message", result.getMessage(),
                    "provider", result.getProvider()
                ));
            }
        } catch (Exception e) {
            log.error("Error triggering test alert: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", e.getMessage() != null ? e.getMessage() : "Error triggering test alert"
            ));
        }
    }
}
