package com.velorix.backend.controller;

import com.velorix.backend.security.JwtUtil;
import com.velorix.backend.service.SseNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SseController {

    @Autowired
    private SseNotificationService sseNotificationService;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        String userId = null;

        // 1. Check SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            userId = auth.getName();
        }

        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }

        log.info("SSE subscription request received for user: {}", userId);
        return sseNotificationService.subscribe(userId);
    }
}
