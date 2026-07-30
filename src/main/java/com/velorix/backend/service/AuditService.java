package com.velorix.backend.service;

import com.velorix.backend.model.AuditEvent;
import com.velorix.backend.repository.AuditEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
public class AuditService {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Async
    public void logEvent(String userId, String eventType, Map<String, Object> metadata) {
        try {
            AuditEvent event = AuditEvent.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .timestamp(LocalDateTime.now())
                    .metadata(metadata)
                    .build();
            auditEventRepository.save(event);
            log.info("Audit log saved: {} for user {}", eventType, userId);
        } catch (Exception e) {
            log.error("Failed to save audit log for user {}: {}", userId, e.getMessage());
        }
    }
}
