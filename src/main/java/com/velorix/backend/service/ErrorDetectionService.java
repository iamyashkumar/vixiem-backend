package com.velorix.backend.service;

import com.velorix.backend.model.ErrorLog;
import com.velorix.backend.repository.ErrorLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ErrorDetectionService {

    @Autowired
    private ErrorLogRepository errorLogRepository;

    @Scheduled(fixedDelay = 300000) // Every 5 minutes
    public void detectErrorSpikes() {
        log.info("Detecting error spikes...");

        try {
            List<ErrorLog> recentErrors = errorLogRepository.findByEnabledTrue();

            if (recentErrors.size() > 10) {
                log.warn("High error rate detected: {} errors in last 5 minutes", recentErrors.size());
            } else {
                log.info("Error spike check completed: {} errors detected", recentErrors.size());
            }
        } catch (Exception e) {
            log.error("Error during error spike detection: {}", e.getMessage());
        }
    }
}