package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "logs")
@CompoundIndexes({
    @CompoundIndex(name = "user_time_idx", def = "{'userId': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "endpoint_time_idx", def = "{'endpointId': 1, 'timestamp': -1}")
})
public class LogEntry {
    @Id
    private String id;
    private String userId;
    private String endpointId;     // Reference to ApiEndpoint
    private String projectId;      // optional, for future multi-project support
    private String level;          // INFO, WARN, ERROR
    private String message;
    private String source;         // service name or component
    private Long responseTimeMs;   // For analytics aggregation
    @Indexed(expireAfterSeconds = 604800) // TTL: 7 days in seconds
    private LocalDateTime timestamp;
}