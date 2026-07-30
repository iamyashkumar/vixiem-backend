package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_endpoints")
public class ApiEndpoint {
    @Id
    private String id;

    private String userId;

    private String name;

    private String url;

    private java.util.List<String> tags;

    private int checkIntervalSeconds;

    @JsonProperty("isActive")
    private boolean isActive;

    private boolean alertsEnabled;
    private Boolean lastStatus; // true = UP, false = DOWN, null = UNKNOWN
    private LocalDateTime statusChangedAt;
    private LocalDateTime lastAlertSentAt;

    private LocalDateTime createdAt;
}