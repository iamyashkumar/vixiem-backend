package com.velorix.backend.controller;

import com.velorix.backend.model.AlertRule;
import com.velorix.backend.repository.AlertRuleRepository;
import jakarta.validation.Valid;
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

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return auth.getName();
    }

    @PostMapping
    public ResponseEntity<?> createRule(@Valid @RequestBody AlertRule rule) {
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

    @GetMapping("/{id}")
    public ResponseEntity<?> getRuleById(@PathVariable String id) {
        String userId = getUserIdFromRequest();
        return alertRuleRepository.findById(id)
                .filter(rule -> userId.equals(rule.getUserId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(@PathVariable String id, @Valid @RequestBody AlertRule updatedRule) {
        String userId = getUserIdFromRequest();
        return alertRuleRepository.findById(id)
                .filter(rule -> userId.equals(rule.getUserId()))
                .map(existingRule -> {
                    existingRule.setName(updatedRule.getName());
                    existingRule.setMetric(updatedRule.getMetric());
                    existingRule.setCondition(updatedRule.getCondition());
                    existingRule.setThreshold(updatedRule.getThreshold());
                    existingRule.setEnabled(updatedRule.isEnabled());
                    AlertRule saved = alertRuleRepository.save(existingRule);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(@PathVariable String id) {
        String userId = getUserIdFromRequest();
        return alertRuleRepository.findById(id)
                .filter(rule -> userId.equals(rule.getUserId()))
                .map(existingRule -> {
                    alertRuleRepository.delete(existingRule);
                    return ResponseEntity.ok(Map.of("message", "Rule deleted successfully"));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}