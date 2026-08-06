package com.velorix.backend.service;

import com.velorix.backend.model.ApiEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private AlertNotificationService alertNotificationService;

    private ApiEndpoint sampleEndpoint;

    @BeforeEach
    void setUp() {
        sampleEndpoint = ApiEndpoint.builder()
                .id("ep123")
                .userId("admin@velorix.com")
                .name("Payment Gateway API")
                .url("https://api.velorix.com/payments")
                .alertsEnabled(true)
                .alertEmail("alerts@velorix.com")
                .discordWebhookUrl("https://discord.com/api/webhooks/12345/testtoken")
                .build();
    }

    @Test
    @DisplayName("Should send email alert when endpoint goes DOWN")
    void testSendDowntimeEmailAlert() {
        assertDoesNotThrow(() -> {
            alertNotificationService.sendDowntimeAlert(sampleEndpoint, true, "503 Service Unavailable");
        });

        verify(mailSender, timeout(1000).atLeastOnce()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Should send email alert when endpoint RECOVERS (UP)")
    void testSendRecoveryEmailAlert() {
        assertDoesNotThrow(() -> {
            alertNotificationService.sendDowntimeAlert(sampleEndpoint, false, null);
        });

        verify(mailSender, timeout(1000).atLeastOnce()).send(any(SimpleMailMessage.class));
    }
}
