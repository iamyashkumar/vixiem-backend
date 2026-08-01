package com.velorix.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BackendApplication {

    public static void main(String[] args) {
        String mongoUri = System.getenv("MONGODB_URI");
        if (mongoUri != null && !mongoUri.trim().isEmpty()) {
            String cleanUri = mongoUri.trim().replaceAll("[\\r\\n]", "");
            System.setProperty("spring.data.mongodb.uri", cleanUri);
        }
        SpringApplication.run(BackendApplication.class, args);
    }

}