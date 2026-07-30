package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    @Indexed(unique = true)
    private String username;

    private String password;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Builder.Default
    private String role = "USER";

    private String profilePicture;

    private String phone;

    @Builder.Default
    private String authProvider = "LOCAL"; // "LOCAL" or "GOOGLE"

    @Builder.Default
    private boolean emailVerified = false;

    // ✅ AI CALL TRACKING
    @Builder.Default
    private int dailyAiCalls = 0;

    private java.util.Date lastPasswordResetDate;

    @Builder.Default
    private int maxDailyAiCalls = 10; // Free tier limit

    private LocalDateTime lastAiCallReset;

    @Builder.Default
    private long totalAiCallsUsed = 0;

    // ✅ SUBSCRIPTION DETAILS
    @Builder.Default
    private String subscriptionPlan = "FREE"; // FREE, PRO, ENTERPRISE

    private LocalDateTime subscriptionStartDate;

    private LocalDateTime subscriptionEndDate;

    @Builder.Default
    private boolean subscriptionActive = false;

    // ✅ API STATS
    @Builder.Default
    private int totalApiCalls = 0;

    private LocalDateTime lastApiCallTime;

    // ✅ PREFERENCES
    @Builder.Default
    private String theme = "dark"; // dark, light

    @Builder.Default
    private String language = "en";

    @Builder.Default
    private boolean notificationsEnabled = true;

    @Override
    public String toString() {
        return "User{" +
                "id='" + id + '\'' +
                ", email='" + email + '\'' +
                ", username='" + username + '\'' +
                ", enabled=" + enabled +
                ", role='" + role + '\'' +
                ", dailyAiCalls=" + dailyAiCalls +
                ", subscriptionPlan='" + subscriptionPlan + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}