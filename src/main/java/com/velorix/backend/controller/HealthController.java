package com.velorix.backend.controller;

import com.velorix.backend.security.JwtUtil;
import com.velorix.backend.service.HealthCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.velorix.backend.model.LogEntry;
import com.velorix.backend.repository.LogRepository;

@RestController
@RequestMapping("/health")
@Slf4j
public class HealthController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private HealthCheckService healthCheckService;

    @Autowired
    private LogRepository logRepository;

    // ✅ Health check endpoint
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());
        response.put("service", "Velorix Backend");
        response.put("version", "1.0.0");

        return ResponseEntity.ok(response);
    }

    // ✅ Get current user info from token
    @GetMapping("/current-user")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).build();
            }
            String userId = auth.getName();

            if (userId == null) {
                return ResponseEntity.status(401).build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("userId", userId);
            response.put("authenticated", true);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting current user: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
    }

    // ✅ Check if endpoint is up
}
