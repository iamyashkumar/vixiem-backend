package com.velorix.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class MongoUriSanitizerPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String mongoUri = environment.getProperty("MONGODB_URI");
        if (mongoUri == null || mongoUri.trim().isEmpty()) {
            mongoUri = environment.getProperty("spring.data.mongodb.uri");
        }

        if (mongoUri != null && !mongoUri.trim().isEmpty()) {
            String cleanUri = mongoUri.trim().replaceAll("[\\r\\n]", "");
            Map<String, Object> map = new HashMap<>();
            map.put("spring.data.mongodb.uri", cleanUri);
            environment.getPropertySources().addFirst(new MapPropertySource("sanitizedMongoProperties", map));
            System.out.println("MONGO URI SANITIZED SUCCESSFULLY: " + cleanUri);
        }
    }
}
