package com.velorix.backend.service;

import com.velorix.backend.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class SseNotificationService {

    private final Map<String, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        String effectiveUserId = (userId != null && !userId.trim().isEmpty()) ? userId : "ANONYMOUS";
        SseEmitter emitter = new SseEmitter(0L); // Infinite timeout

        userEmitters.computeIfAbsent(effectiveUserId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(effectiveUserId, emitter));
        emitter.onTimeout(() -> removeEmitter(effectiveUserId, emitter));
        emitter.onError((ex) -> removeEmitter(effectiveUserId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("Connected to Vixiem Live Event Stream"));
            log.info("Client connected to SSE for userId: {}", effectiveUserId);
        } catch (IOException e) {
            log.warn("Failed to send initial SSE payload to user {}: {}", effectiveUserId, e.getMessage());
            removeEmitter(effectiveUserId, emitter);
        }

        return emitter;
    }

    public void broadcastLog(String userId, LogEntry logEntry) {
        sendToUser(userId, logEntry);
        // Also send to ANONYMOUS / fallback emitters if any
        if (!"ANONYMOUS".equals(userId)) {
            sendToUser("ANONYMOUS", logEntry);
        }
    }

    private void sendToUser(String userId, LogEntry logEntry) {
        if (userId == null) return;
        List<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("LOG_ENTRY")
                        .data(logEntry));
            } catch (Exception e) {
                log.debug("SSE emitter send error for user {}: {}", userId, e.getMessage());
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        List<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
            }
        }
        log.debug("Removed SSE Emitter for user: {}", userId);
    }
}
