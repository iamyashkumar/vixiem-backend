package com.velorix.backend.controller;

import com.velorix.backend.dto.ApiResponse;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;
import com.velorix.backend.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.velorix.backend.service.AiQuotaService;
import com.velorix.backend.service.AiDebugService;

@RestController
@RequestMapping("/api/ai")
@Slf4j
public class AiController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AiQuotaService aiQuotaService;

    @Autowired
    private AiDebugService aiDebugService;

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getName();
    }

    // ✅ Get AI Call Limit Status
    @GetMapping("/limit-status")
    public ResponseEntity<Map<String, Object>> getLimitStatus() {
        try {
            String userId = getUserIdFromRequest();

            if (userId == null) {
                return ResponseEntity.status(401).build();
            }

            Optional<User> userOpt = userRepository.findByEmail(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).build();
            }

            User user = userOpt.get();

            // Check if daily limit needs reset
            if (user.getLastAiCallReset() == null ||
                    user.getLastAiCallReset().isBefore(LocalDateTime.now().minusHours(24))) {
                user.setDailyAiCalls(0);
                user.setLastAiCallReset(LocalDateTime.now());
                userRepository.save(user);
            }

            Map<String, Object> response = new HashMap<>();
            int maxCalls = user.getMaxDailyAiCalls() > 0 ? user.getMaxDailyAiCalls() : 50;
            response.put("dailyCallsUsed", user.getDailyAiCalls());
            response.put("used", user.getDailyAiCalls()); // UI expects 'used'
            response.put("limit", maxCalls); // UI expects 'limit'
            response.put("dailyCallsLimit", maxCalls);
            response.put("remainingCalls", maxCalls - user.getDailyAiCalls());
            response.put("subscriptionPlan", user.getSubscriptionPlan());
            response.put("totalCallsUsed", user.getTotalAiCallsUsed());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting limit status: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ✅ Analyze Errors with AI
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyzeErrors(
            @RequestBody Map<String, Object> payload) {

        String userId = getUserIdFromRequest();

        if (userId == null) {
            return ResponseEntity.status(401).build();
        }

        // Enforce quota atomically
        boolean quotaReserved = aiQuotaService.reserveQuota(userId);
        if (!quotaReserved) {
            log.warn("User {} reached daily AI call limit", userId);
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Daily AI call limit reached. Try tomorrow or upgrade your plan.",
                    "code", "QUOTA_EXCEEDED"
            ));
        }

        String endpointId = (String) payload.getOrDefault("endpointId", "unknown");
        List<String> errors = (List<String>) payload.get("errors");

        Map<String, Object> analysis = aiDebugService.analyzeErrors(userId, endpointId, errors);

        // Fetch user to get remaining calls (or could just return without it, but let's include it)
        Optional<User> userOpt = userRepository.findByEmail(userId);
        int remaining = userOpt.map(u -> (u.getMaxDailyAiCalls() > 0 ? u.getMaxDailyAiCalls() : 50) - u.getDailyAiCalls()).orElse(0);

        Map<String, Object> response = new HashMap<>();
        response.put("analysis", analysis);
        response.put("callsRemaining", remaining);
        response.put("success", true);

        return ResponseEntity.ok(response);
    }

    // ✅ Get AI Usage Stats
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        try {
            String userId = getUserIdFromRequest();

            if (userId == null) {
                return ResponseEntity.status(401).build();
            }

            Optional<User> userOpt = userRepository.findByEmail(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).build();
            }

            User user = userOpt.get();

            Map<String, Object> response = new HashMap<>();
            response.put("totalAiCalls", user.getTotalAiCallsUsed());
            response.put("todayAiCalls", user.getDailyAiCalls());
            response.put("subscriptionPlan", user.getSubscriptionPlan());
            response.put("accountCreated", user.getCreatedAt());
            response.put("lastAiCallReset", user.getLastAiCallReset());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting stats: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}