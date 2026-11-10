package com.velorix.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "workspaces")
public class Workspace {
    @Id
    private String id;

    private String userId;

    @NotBlank(message = "Workspace name is required")
    @Size(max = 80, message = "Workspace name must be 80 characters or fewer")
    private String name;

    private String slug;

    @Builder.Default
    private boolean isDefault = false;

    private LocalDateTime createdAt;

    public boolean isDefault() {
        return isDefault;
    }

    public boolean getIsDefault() {
        return isDefault;
    }
}
