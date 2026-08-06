package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_endpoints")
public class ApiEndpoint {
    @Id
    private String id;

    private String userId;

    @NotBlank(message = "Endpoint name is required")
    @Size(max = 100, message = "Endpoint name must be 100 characters or fewer")
    private String name;

    @NotBlank(message = "Endpoint URL is required")
    @Size(max = 2048, message = "Endpoint URL must be 2048 characters or fewer")
    private String url;

    private java.util.List<String> tags;

    private int checkIntervalSeconds;

    @JsonProperty("isActive")
    private boolean isActive;

    private boolean alertsEnabled;
    private String discordWebhookUrl;
    private String alertEmail;
    private Boolean lastStatus; // true = UP, false = DOWN, null = UNKNOWN
    private LocalDateTime statusChangedAt;
    private LocalDateTime lastAlertSentAt;

    private LocalDateTime createdAt;
}
