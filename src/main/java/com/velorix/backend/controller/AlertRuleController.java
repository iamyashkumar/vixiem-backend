package com.velorix.backend.controller;

import com.velorix.backend.model.AlertRule;
import com.velorix.backend.repository.AlertRuleRepository;
import com.velorix.backend.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rules")

public class AlertRuleController {

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return auth.getName();
    }

    @PostMapping
    public ResponseEntity<?> createRule(@RequestBody AlertRule rule) {
        String userId = getUserIdFromRequest();
        rule.setUserId(userId);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setEnabled(true);
        AlertRule saved = alertRuleRepository.save(rule);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<AlertRule>> getUserRules() {
        String userId = getUserIdFromRequest();
        return ResponseEntity.ok(alertRuleRepository.findByUserId(userId));
    }
}