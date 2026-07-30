package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "error_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorLog {

    @Id
    private String id;

    private String endpoint;

    private String errorMessage;

    private String stackTrace;

    private String userId;

    private int statusCode;

    private LocalDateTime timestamp;

    @Builder.Default
    private boolean enabled = true;
}