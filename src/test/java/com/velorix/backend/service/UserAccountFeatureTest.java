package com.velorix.backend.service;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.LogEntry;
import com.velorix.backend.model.User;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.repository.LogRepository;
import com.velorix.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UserAccountFeatureTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private HealthCheckService healthCheckService;

    @Autowired
    private SseNotificationService sseNotificationService;

    @Autowired
    private AlertNotificationService alertNotificationService;

    private User targetUser;

    @BeforeEach
    void setUp() {
        String targetEmail = "dd7@gmail.com";
        Optional<User> userOpt = userRepository.findByEmail(targetEmail);
        if (userOpt.isPresent()) {
            targetUser = userOpt.get();
        } else {
            targetUser = User.builder()
                    .email(targetEmail)
                    .username("ddddd7")
                    .password("Xyz@12345")
                    .enabled(true)
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .emailVerified(true)
                    .build();
            userRepository.save(targetUser);
        }
    }

    @Test
    @DisplayName("Test Enterprise Alerting and SSE Broadcasting for user account dd7@gmail.com")
    void testEnterpriseFeaturesForUserAccount() {
        assertNotNull(targetUser, "Target user should exist in repository");

        // 1. Create endpoint with custom Discord Webhook & Alert Email
        ApiEndpoint endpoint = ApiEndpoint.builder()
                .userId(targetUser.getId())
                .name("Production Gateway Test")
                .url("https://httpbin.org/status/200")
                .checkIntervalSeconds(30)
                .isActive(true)
                .alertsEnabled(true)
                .alertEmail("dd7@gmail.com")
                .discordWebhookUrl("https://discord.com/api/webhooks/mock/test")
                .lastStatus(null)
                .build();

        ApiEndpoint savedEndpoint = apiEndpointRepository.save(endpoint);
        assertNotNull(savedEndpoint.getId(), "Endpoint should be saved with ID");

        // 2. Subscribe to SSE stream for dd7@gmail.com
        assertDoesNotThrow(() -> {
            var emitter = sseNotificationService.subscribe(targetUser.getId());
            assertNotNull(emitter);
        });

        // 3. Trigger Alert Notification test for dd7@gmail.com
        assertDoesNotThrow(() -> {
            alertNotificationService.sendDowntimeAlert(savedEndpoint, true, "HTTP 503 Connection Refused");
            alertNotificationService.sendDowntimeAlert(savedEndpoint, false, null);
        });

        // 4. Verify SSE log broadcast
        LogEntry logEntry = LogEntry.builder()
                .userId(targetUser.getId())
                .endpointId(savedEndpoint.getId())
                .level("ERROR")
                .source(savedEndpoint.getName())
                .message("Endpoint is DOWN. Connection timed out after 5000ms")
                .timestamp(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> sseNotificationService.broadcastLog(targetUser.getId(), logEntry));

        // Cleanup test endpoint
        apiEndpointRepository.deleteById(savedEndpoint.getId());
    }
}
