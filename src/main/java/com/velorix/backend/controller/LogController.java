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

    private List<String> getUserIdsFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        String email = auth.getName();
        java.util.Optional<com.velorix.backend.model.User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            return List.of(userOpt.get().getId(), email);
        }
        return List.of(email);
    }

    @PostMapping
    public ResponseEntity<?> ingestLog(@RequestBody LogEntry logEntry) {
        List<String> userIds = getUserIdsFromRequest();
        logEntry.setUserId(userIds.get(0));
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

        List<String> userIds = getUserIdsFromRequest();

        // Build query criteria
        Criteria criteria = Criteria.where("userId").in(userIds);
        if (level != null && !level.isEmpty()) {
            criteria = criteria.and("level").is(level);
        }
        if (keyword != null && !keyword.isEmpty()) {
            criteria = criteria.and("message").regex(keyword, "i"); // case‑insensitive search
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, LogEntry.class);
        
        log.debug("User IDs: {}, Level: {}, Keyword: {}", userIds, level, keyword);
        log.debug("Total logs found in DB for this user: {}", total);

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
        List<String> userIds = getUserIdsFromRequest();
        
        java.util.Optional<LogEntry> logOpt = logRepository.findById(id);
        if (logOpt.isPresent() && userIds.contains(logOpt.get().getUserId())) {
            return ResponseEntity.ok(logOpt.get());
        }
        return ResponseEntity.notFound().build();
    }
}