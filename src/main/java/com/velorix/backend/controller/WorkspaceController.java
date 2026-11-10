package com.velorix.backend.controller;

import com.velorix.backend.model.ApiEndpoint;
import com.velorix.backend.model.User;
import com.velorix.backend.model.Workspace;
import com.velorix.backend.repository.ApiEndpointRepository;
import com.velorix.backend.repository.UserRepository;
import com.velorix.backend.repository.WorkspaceRepository;
import com.velorix.backend.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @Autowired
    private UserRepository userRepository;

    private String getUserIdFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new RuntimeException("User not authenticated");
        }
        if (auth.getPrincipal() instanceof CustomUserDetails) {
            return ((CustomUserDetails) auth.getPrincipal()).getUser().getId();
        }
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElse(email);
    }

    private List<String> getUserIdsFromRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new RuntimeException("User not authenticated");
        }
        String email = auth.getName();
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            return List.of(userOpt.get().getId(), email);
        }
        return List.of(email);
    }

    /**
     * GET /api/workspaces - Fetch all workspaces for the authenticated user
     */
    @GetMapping
    public ResponseEntity<List<Workspace>> getWorkspaces() {
        try {
            List<String> userIds = getUserIdsFromRequest();
            String primaryUserId = getUserIdFromRequest();

            List<Workspace> workspaces = workspaceRepository.findByUserIdIn(userIds);

            // Ensure a default workspace always exists for user
            boolean hasDefault = workspaces.stream().anyMatch(w -> Boolean.TRUE.equals(w.getIsDefault()) || w.isDefault());
            Workspace defaultWs;

            if (!hasDefault) {
                Workspace newDefault = Workspace.builder()
                        .userId(primaryUserId)
                        .name("Production Fleet")
                        .slug("production-fleet")
                        .isDefault(true)
                        .createdAt(LocalDateTime.now())
                        .build();
                defaultWs = workspaceRepository.save(newDefault);
                workspaces = workspaceRepository.findByUserIdIn(userIds);
                log.info("Auto-provisioned default workspace for user {}: {}", primaryUserId, defaultWs.getId());
            } else {
                defaultWs = workspaces.stream()
                        .filter(w -> Boolean.TRUE.equals(w.getIsDefault()) || w.isDefault())
                        .findFirst()
                        .orElse(workspaces.get(0));
            }

            // Link any legacy unassigned endpoints (workspaceId == null) to the default workspace
            List<ApiEndpoint> userEndpoints = apiEndpointRepository.findByUserIdIn(userIds);
            boolean linkedAny = false;
            for (ApiEndpoint ep : userEndpoints) {
                if (ep.getWorkspaceId() == null || ep.getWorkspaceId().trim().isEmpty()) {
                    ep.setWorkspaceId(defaultWs.getId());
                    apiEndpointRepository.save(ep);
                    linkedAny = true;
                }
            }
            if (linkedAny) {
                log.info("Linked legacy endpoints to default workspace {} for user {}", defaultWs.getId(), primaryUserId);
            }

            return ResponseEntity.ok(workspaces);
        } catch (Exception e) {
            log.error("Error fetching workspaces: {}", e.getMessage());
            return ResponseEntity.status(401).body(null);
        }
    }

    /**
     * POST /api/workspaces - Create a new workspace
     */
    @PostMapping
    public ResponseEntity<?> createWorkspace(@RequestBody Map<String, String> body) {
        try {
            String primaryUserId = getUserIdFromRequest();
            List<String> userIds = getUserIdsFromRequest();

            Optional<User> userOpt = userRepository.findById(primaryUserId);
            if (userOpt.isEmpty()) {
                userOpt = userRepository.findByEmail(primaryUserId);
            }
            User user = userOpt.orElse(null);

            long currentActiveCount = workspaceRepository.countByUserIdIn(userIds);
            boolean isPaid = user != null && ("PRO".equalsIgnoreCase(user.getSubscriptionPlan()) 
                    || "ENTERPRISE".equalsIgnoreCase(user.getSubscriptionPlan()) 
                    || user.isSubscriptionActive());

            if (!isPaid) {
                int lifetimeCreated = user != null ? user.getTotalWorkspacesCreated() : (int) currentActiveCount;
                if (lifetimeCreated >= 3 || currentActiveCount >= 3) {
                    log.warn("Workspace limit blocked for user {}: lifetime={}, active={}", primaryUserId, lifetimeCreated, currentActiveCount);
                    return ResponseEntity.status(403).body(Map.of(
                            "error", "Workspace limit reached (3/3). Free tier allows a maximum of 3 workspaces. Please upgrade to Pro for unlimited workspaces.",
                            "code", "WORKSPACE_LIMIT_EXCEEDED"
                    ));
                }
            }

            String name = body.get("name");
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace name is required"));
            }

            String cleanName = name.trim();
            String slug = cleanName.toLowerCase().replaceAll("[^a-z0-9]+", "-");

            Workspace ws = Workspace.builder()
                    .userId(primaryUserId)
                    .name(cleanName)
                    .slug(slug)
                    .isDefault(false)
                    .createdAt(LocalDateTime.now())
                    .build();

            Workspace saved = workspaceRepository.save(ws);

            if (user != null) {
                user.setTotalWorkspacesCreated(Math.max(user.getTotalWorkspacesCreated(), (int) currentActiveCount) + 1);
                userRepository.save(user);
            }

            log.info("Workspace created: {} for user: {} (lifetime total: {})", saved.getName(), primaryUserId, user != null ? user.getTotalWorkspacesCreated() : "N/A");
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Error creating workspace: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/workspaces/{id} - Delete workspace
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWorkspace(@PathVariable String id) {
        try {
            List<String> userIds = getUserIdsFromRequest();
            Optional<Workspace> wsOpt = workspaceRepository.findByIdAndUserIdIn(id, userIds);
            if (wsOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "Workspace not found or unauthorized"));
            }

            long totalCount = workspaceRepository.countByUserIdIn(userIds);
            if (totalCount <= 1) {
                return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete your only remaining workspace"));
            }

            workspaceRepository.delete(wsOpt.get());
            log.info("Workspace deleted: {}", id);
            return ResponseEntity.ok(Map.of("message", "Workspace deleted successfully"));
        } catch (Exception e) {
            log.error("Error deleting workspace: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
