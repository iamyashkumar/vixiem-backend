package com.velorix.backend.controller;

import com.velorix.backend.model.LogEntry;
import com.velorix.backend.repository.LogRepository;
import com.velorix.backend.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.velorix.backend.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping({"/logs", "/api/logs"})
public class LogController {

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return auth.getName();
    }

    @PostMapping
    public ResponseEntity<?> ingestLog(@RequestBody LogEntry logEntry) {
        String userId = getUserIdFromRequest();
        logEntry.setUserId(userId);
        logEntry.setTimestamp(LocalDateTime.now());
        logRepository.save(logEntry);
        return ResponseEntity.ok(Map.of("message", "Log ingested"));
    }

    @GetMapping
    public ResponseEntity<?> getLogs(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = getUserIdFromRequest();

        // Build query criteria
        Criteria criteria = Criteria.where("userId").is(userId);
        if (level != null && !level.isEmpty()) {
            criteria = criteria.and("level").is(level);
        }
        if (keyword != null && !keyword.isEmpty()) {
            criteria = criteria.and("message").regex(keyword, "i"); // case‑insensitive search
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, LogEntry.class);
        
        log.info("DEBUG: User ID: {}, Level: {}, Keyword: {}", userId, level, keyword);
        log.info("DEBUG: Total logs found in DB for this user: {}", total);

        query.with(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));
        List<LogEntry> logs = mongoTemplate.find(query, LogEntry.class);

        int totalPages = (int) Math.ceil((double) total / size);
        return ResponseEntity.ok(Map.of(
                "content", logs,
                "totalPages", totalPages,
                "totalElements", total,
                "page", page,
                "size", size
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getLogById(@PathVariable String id) {
        String userId = getUserIdFromRequest();
        
        return logRepository.findByIdAndUserId(id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}