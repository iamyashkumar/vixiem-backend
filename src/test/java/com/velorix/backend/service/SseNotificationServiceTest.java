package com.velorix.backend.service;

import com.velorix.backend.model.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SseNotificationServiceTest {

    private SseNotificationService sseNotificationService;

    @BeforeEach
    void setUp() {
        sseNotificationService = new SseNotificationService();
    }

    @Test
    @DisplayName("Should create active SseEmitter for user subscription")
    void testSubscribe() {
        SseEmitter emitter = sseNotificationService.subscribe("admin@velorix.com");
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("Should broadcast log entry without throwing exceptions")
    void testBroadcastLog() {
        SseEmitter emitter = sseNotificationService.subscribe("admin@velorix.com");
        
        LogEntry logEntry = LogEntry.builder()
                .id("log1")
                .userId("admin@velorix.com")
                .endpointId("ep1")
                .level("INFO")
                .source("Payment Gateway API")
                .message("Endpoint is UP. Response time: 42ms")
                .timestamp(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> sseNotificationService.broadcastLog("admin@velorix.com", logEntry));
    }
}
