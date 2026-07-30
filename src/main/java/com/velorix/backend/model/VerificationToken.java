package com.velorix.backend.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "verification_tokens")
public class VerificationToken {

    @Id
    private String id;

    private String token;
    
    private String userEmail;

    private LocalDateTime expiryDate;
    
    private LocalDateTime createdAt;
}
