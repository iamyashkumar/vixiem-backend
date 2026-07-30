package com.velorix.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class EmailService {

    @Autowired(required = false) // Optional in case properties aren't set
    private JavaMailSender mailSender;

    @Async
    public void sendAlertEmail(String to, String endpointName, String url, boolean isDown) {
        if (mailSender == null) {
            log.warn("JavaMailSender is not configured. Cannot send email alert for {}.", endpointName);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            if (isDown) {
                message.setSubject("🚨 URGENT: Your API Endpoint is DOWN");
                message.setText(String.format(
                        "Hello,\n\nYour monitored endpoint '%s' (%s) went DOWN at %s.\n\nPlease check your dashboard for more details.",
                        endpointName, url, LocalDateTime.now()
                ));
            } else {
                message.setSubject("✅ RECOVERY: Your API Endpoint is UP");
                message.setText(String.format(
                        "Hello,\n\nYour monitored endpoint '%s' (%s) has recovered and is UP again as of %s.\n\nAll systems are operating normally.",
                        endpointName, url, LocalDateTime.now()
                ));
            }

            mailSender.send(message);
            log.info("Alert email sent successfully to {} for endpoint {}", to, endpointName);
        } catch (Exception e) {
            log.error("Failed to send alert email to {}: {}", to, e.getMessage());
        }
    }
}
