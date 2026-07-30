package com.velorix.backend.service;

import com.velorix.backend.model.User;
import com.velorix.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class DataSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting data seeding...");

        if (userRepository.count() == 0) {
            createDefaultUsers();
            log.info("Data seeding completed successfully!");
        } else {
            log.info("Database already has data. Skipping seeding.");
        }
    }

    private void createDefaultUsers() {
        try {
            // Create admin user
            User admin = User.builder()
                    .email("admin@velorix.com")
                    .username("admin")
                    .password(passwordEncoder.encode("Admin@123456"))
                    .enabled(true)
                    .role("ADMIN")
                    .createdAt(LocalDateTime.now())
                    .emailVerified(true)
                    .build();

            userRepository.save(admin);
            log.info("Created admin user");

            // Create test user
            User testUser = User.builder()
                    .email("test@velorix.com")
                    .username("testuser")
                    .password(passwordEncoder.encode("Test@123456"))
                    .enabled(true)
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .emailVerified(false)
                    .build();

            userRepository.save(testUser);
            log.info("Created test user");

        } catch (Exception e) {
            log.error("Error during data seeding: {}", e.getMessage());
        }
    }
}