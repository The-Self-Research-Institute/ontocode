package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public WorkspaceController(WorkspaceService workspaceService, 
                              UserRepository userRepository,
                              JwtUtil jwtUtil) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Get all workspaces for the authenticated user
     */
    @GetMapping
    public ResponseEntity<?> getUserWorkspaces() {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            List<Workspace> workspaces = workspaceService.getUserWorkspaces(user.getId());

            // Convert to DTO to avoid sending sensitive info
            List<Map<String, Object>> workspaceDTOs = workspaces.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "workspaces", workspaceDTOs,
                "userId", user.getId()
            ));
        } catch (Exception e) {
            log.error("Error fetching workspaces", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check if workspace name already exists for user
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkWorkspaceExists(@RequestParam String name) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            List<Workspace> userWorkspaces = workspaceService.getUserWorkspaces(user.getId());
            
            // Check if workspace with same name exists
            Optional<Workspace> existingWorkspace = userWorkspaces.stream()
                    .filter(w -> w.getName().equalsIgnoreCase(name.trim()))
                    .findFirst();
            
            if (existingWorkspace.isPresent()) {
                return ResponseEntity.ok(Map.of(
                    "exists", true,
                    "name", name,
                    "existingWorkspace", Map.of(
                        "id", existingWorkspace.get().getId(),
                        "name", existingWorkspace.get().getName(),
                        "createdAt", existingWorkspace.get().getCreatedAt()
                    )
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "exists", false,
                "name", name
            ));
        } catch (Exception e) {
            log.error("Error checking workspace existence", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create a new workspace
     */
    @PostMapping
    public ResponseEntity<?> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Validate subscription plan if provided
            String subscriptionPlan = request.subscriptionPlan;
            if (subscriptionPlan == null || subscriptionPlan.isEmpty()) {
                subscriptionPlan = "free"; // Default to free plan
            } else if (!subscriptionPlan.matches("free|pro|enterprise")) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid subscription plan. Must be 'free', 'pro', or 'enterprise'"
                ));
            }
            
            Workspace workspace = workspaceService.createWorkspace(
                user.getId(), 
                request.name, 
                request.description
            );
            
            // Set subscription plan and apply limits
            workspace.setSubscriptionPlan(subscriptionPlan);
            workspace.setSubscriptionStartDate(LocalDateTime.now());
            
            // Apply plan-specific limits
            switch (subscriptionPlan) {
                case "free":
                    workspace.setMaxMembers(3);
                    workspace.setMaxWorkspaces(1);
                    workspace.setCollaborationEnabled(false);
                    break;
                case "pro":
                    workspace.setMaxMembers(20);
                    workspace.setMaxWorkspaces(5);
                    workspace.setCollaborationEnabled(true);
                    break;
                case "enterprise":
                    workspace.setMaxMembers(Integer.MAX_VALUE);
                    workspace.setMaxWorkspaces(Integer.MAX_VALUE);
                    workspace.setCollaborationEnabled(true);
                    break;
            }
            
            // Save updated workspace with subscription plan
            workspace = workspaceService.updateWorkspace(workspace);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace created successfully",
                "workspace", convertToDTO(workspace)
            ));
        } catch (Exception e) {
            log.error("Error creating workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get workspace subscription details
     */
    @GetMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> getSubscription(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify user has access to workspace
            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "You don't have access to this workspace"
                ));
            }

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            
            Map<String, Object> subscription = new HashMap<>();
            subscription.put("plan", workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "free");
            subscription.put("maxMembers", workspace.getMaxMembers());
            subscription.put("maxWorkspaces", workspace.getMaxWorkspaces());
            subscription.put("collaborationEnabled", workspace.getCollaborationEnabled());
            subscription.put("startDate", workspace.getSubscriptionStartDate());
            subscription.put("endDate", workspace.getSubscriptionEndDate());

            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            log.error("Error getting subscription details", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update workspace subscription plan
     */
    @PatchMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> updateSubscription(
            @PathVariable String workspaceId,
            @RequestBody Map<String, String> request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify user is the owner of the workspace
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            if (!workspace.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owner can update subscription plan"
                ));
            }

            String plan = request.get("plan");
            if (plan == null || !plan.matches("free|pro|enterprise")) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid plan. Must be 'free', 'pro', or 'enterprise'"
                ));
            }

            // Update subscription plan
            workspace.setSubscriptionPlan(plan);
            workspace.setUpdatedAt(LocalDateTime.now());
            
            // Update collaboration and limits based on plan
            switch (plan) {
                case "free":
                    workspace.setMaxMembers(3);
                    workspace.setMaxWorkspaces(1);
                    workspace.setCollaborationEnabled(false);
                    break;
                case "pro":
                    workspace.setMaxMembers(20);
                    workspace.setMaxWorkspaces(5);
                    workspace.setCollaborationEnabled(true);
                    break;
                case "enterprise":
                    workspace.setMaxMembers(null); // unlimited
                    workspace.setMaxWorkspaces(null); // unlimited
                    workspace.setCollaborationEnabled(true);
                    break;
            }
            
            workspace.setSubscriptionStartDate(LocalDateTime.now());
            Workspace updatedWorkspace = workspaceService.updateWorkspace(workspace);

            log.info("Updated subscription plan for workspace {} to {}", workspaceId, plan);

            return ResponseEntity.ok(Map.of(
                "message", "Subscription plan updated successfully",
                "workspace", convertToDTO(updatedWorkspace)
            ));
        } catch (IllegalArgumentException e) {
            log.error("Invalid argument for subscription update: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating subscription plan for workspace: {}", workspaceId, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to update subscription plan: " + e.getMessage());
            errorResponse.put("details", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Select a workspace and generate a workspace-scoped JWT token
     */
    @PostMapping("/{workspaceId}/select")
    public ResponseEntity<?> selectWorkspace(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            // Verify user has access to workspace
            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "You don't have access to this workspace"
                ));
            }

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            WorkspaceRole role = workspaceService.getMemberRole(workspaceId, user.getId());

            // Generate workspace-scoped JWT token with subscription plan
            Map<String, Object> claims = new HashMap<>();
            claims.put("workspaceId", workspaceId);
            claims.put("workspaceName", workspace.getName());
            claims.put("workspaceRole", role.toString());
            claims.put("userId", user.getId());
            claims.put("subscriptionPlan", workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "free");

            String token = jwtUtil.generateToken(username, claims);

            return ResponseEntity.ok(Map.of(
                "jwt", token,
                "username", username,
                "workspaceId", workspaceId,
                "workspaceName", workspace.getName(),
                "role", role.toString(),
                "subscriptionPlan", workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "free"
            ));
        } catch (Exception e) {
            log.error("Error selecting workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get workspace details
     */
    @GetMapping("/{workspaceId}")
    public ResponseEntity<?> getWorkspace(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "You don't have access to this workspace"
                ));
            }

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            return ResponseEntity.ok(convertToDTO(workspaceOpt.get()));
        } catch (Exception e) {
            log.error("Error fetching workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a workspace
     */
    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<?> deleteWorkspace(@PathVariable String workspaceId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            // Verify user is the owner of the workspace
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            if (!workspace.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owner can delete the workspace"
                ));
            }

            // Delete the workspace
            workspaceService.deleteWorkspace(workspaceId);

            log.info("Workspace {} deleted by user {}", workspaceId, username);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace deleted successfully"
            ));
        } catch (Exception e) {
            log.error("Error deleting workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Remove a member from a workspace (owner only)
     */
    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable String workspaceId,
            @PathVariable String userId) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            // Verify user is the owner of the workspace
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            if (!workspace.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owner can remove members"
                ));
            }

            // Prevent owner from removing themselves
            if (userId.equals(user.getId())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace owner cannot be removed. Please transfer ownership or delete the workspace."
                ));
            }

            // Remove member from workspace
            workspaceService.removeMember(workspaceId, userId);

            log.info("Member {} removed from workspace {} by owner {}", userId, workspaceId, username);

            return ResponseEntity.ok(Map.of(
                "message", "Member removed successfully"
            ));
        } catch (Exception e) {
            log.error("Error removing member from workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // Helper methods
    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }

    private Map<String, Object> convertToDTO(Workspace workspace) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", workspace.getId());
        dto.put("workspaceId", workspace.getWorkspaceId());
        dto.put("name", workspace.getName());
        dto.put("description", workspace.getDescription());
        dto.put("ownerId", workspace.getOwnerId());
        dto.put("memberCount", workspace.getMembers().size());
        dto.put("subscriptionPlan", workspace.getSubscriptionPlan());
        dto.put("collaborationEnabled", workspace.getCollaborationEnabled());
        dto.put("createdAt", workspace.getCreatedAt());
        dto.put("updatedAt", workspace.getUpdatedAt());
        
        // Include member details
        List<Map<String, Object>> members = workspace.getMembers().stream()
                .map(m -> {
                    Map<String, Object> memberDTO = new HashMap<>();
                    memberDTO.put("userId", m.getUserId());
                    memberDTO.put("username", m.getUsername());
                    memberDTO.put("email", m.getEmail());
                    memberDTO.put("role", m.getRole().toString());
                    memberDTO.put("joinedAt", m.getJoinedAt());
                    memberDTO.put("status", m.getStatus() != null ? m.getStatus().toString() : "ACTIVE");
                    memberDTO.put("invitationToken", m.getInvitationToken());
                    return memberDTO;
                })
                .collect(Collectors.toList());
        dto.put("members", members);

        return dto;
    }

    // DTOs
    public static class CreateWorkspaceRequest {
        public String name;
        public String description;
        public String subscriptionPlan; // free, pro, or enterprise
    }
}
