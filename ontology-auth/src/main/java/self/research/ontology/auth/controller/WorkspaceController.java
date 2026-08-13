package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.dto.WorkspaceRequests;
import self.research.ontology.auth.dto.WorkspaceRequests.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.SystemSettingsService;
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
    private final SystemSettingsService systemSettingsService;

    public WorkspaceController(WorkspaceService workspaceService,
                              UserRepository userRepository,
                              JwtUtil jwtUtil,
                              SystemSettingsService systemSettingsService) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.systemSettingsService = systemSettingsService;
    }

    @GetMapping
    public ResponseEntity<?> getUserWorkspaces() {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

            workspaceService.syncWorkspacesToOwnerPlan(user);

            List<Workspace> workspaces = workspaceService.getUserWorkspaces(user.getId());

            workspaces.stream()
                    .map(Workspace::getOwnerId)
                    .distinct()
                    .forEach(ownerId -> userRepository.findById(ownerId).ifPresent(workspaceService::syncWorkspacesToOwnerPlan));
            workspaces = workspaceService.getUserWorkspaces(user.getId());

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

    @GetMapping("/check")
    public ResponseEntity<?> checkWorkspaceExists(@RequestParam String name) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            List<Workspace> userWorkspaces = workspaceService.getUserWorkspaces(user.getId());

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

    @PostMapping
    public ResponseEntity<?> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String username = user.getUsername();

            String rawAccountPlan = user.getSubscriptionPlanName() != null
                    ? user.getSubscriptionPlanName().toUpperCase() : "FREE";
            String accountStatus = user.getSubscriptionStatus();

            if (systemSettingsService.isEnterpriseBypass(user.getEmail())) {
                rawAccountPlan = "ENTERPRISE";
                accountStatus = "active";
            }

            String activeAccountPlan = ("active".equalsIgnoreCase(accountStatus)
                    || "trialing".equalsIgnoreCase(accountStatus))
                    ? rawAccountPlan : "FREE";

            List<Workspace> ownedWorkspaces = workspaceService.getOwnedWorkspaces(user.getId());
            int maxWorkspaces = getMaxWorkspacesForPlan(activeAccountPlan);
            log.info("createWorkspace: user={} planName={} status={} activePlan={} ownedCount={} maxAllowed={}",
                    username, rawAccountPlan, accountStatus, activeAccountPlan, ownedWorkspaces.size(), maxWorkspaces);
            if (ownedWorkspaces.size() >= maxWorkspaces) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace limit reached (" + maxWorkspaces + " workspaces on " + activeAccountPlan + " plan). Upgrade your account to create more.",
                    "currentCount", ownedWorkspaces.size(),
                    "maxAllowed", maxWorkspaces,
                    "accountPlan", activeAccountPlan
                ));
            }

            boolean nameExists = ownedWorkspaces.stream()
                .anyMatch(w -> w.getName().equalsIgnoreCase(request.getName()));
            if (nameExists) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "A workspace with this name already exists"
                ));
            }

            Workspace workspace = workspaceService.createWorkspace(
                user.getId(),
                request.getName(),
                request.getDescription()
            );

            workspace.setMaxWorkspaces(maxWorkspaces);
            workspace = workspaceService.updateWorkspace(workspace);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace created successfully",
                "workspace", convertToDTO(workspace)
            ));
        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> getSubscription(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

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

            Workspace workspace = workspaceOpt.get();

            String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";

            Map<String, Object> subscription = new HashMap<>();
            subscription.put("plan", plan);
            subscription.put("maxMembers", workspace.getMaxMembers());
            subscription.put("maxWorkspaces", workspace.getMaxWorkspaces());
            subscription.put("collaborationEnabled", workspace.getCollaborationEnabled());
            subscription.put("collaborationLevel", getCollaborationLevel(plan));
            subscription.put("hasBasicCollaboration", hasBasicCollaboration(plan));
            subscription.put("hasAdvancedCollaboration", hasAdvancedCollaboration(plan));
            subscription.put("startDate", workspace.getSubscriptionStartDate());
            subscription.put("endDate", workspace.getSubscriptionEndDate());

            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            log.error("Error getting subscription details", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{workspaceId}/subscription")
    public ResponseEntity<?> updateSubscription(
            @PathVariable String workspaceId,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String username = user.getUsername();

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

            String plan = request.getSubscriptionPlan();
            String currentPlan = workspace.getSubscriptionPlan();

            if (isUpgrade(currentPlan, plan)) {
                String stripeStatus = user.getSubscriptionStatus();
                String stripePlan  = user.getSubscriptionPlanName();
                boolean hasActiveStripeSub =
                        user.getStripeSubscriptionId() != null
                        && ("active".equalsIgnoreCase(stripeStatus) || "trialing".equalsIgnoreCase(stripeStatus))
                        && stripePlan != null
                        && planTier(stripePlan) >= planTier(plan);

                if (!hasActiveStripeSub) {
                    log.warn("Blocked direct upgrade for workspace {} — user {} has no matching Stripe subscription (status={}, stripePlan={})",
                            workspaceId, username, stripeStatus, stripePlan);
                    return ResponseEntity.status(402).body(Map.of(
                        "error", "Payment required. Please complete the billing setup to upgrade your plan.",
                        "requiresBilling", true,
                        "currentPlan", currentPlan != null ? currentPlan : "FREE",
                        "requestedPlan", plan
                    ));
                }
            }

            if (isDowngrade(currentPlan, plan)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Downgrading is not permitted. You can only upgrade or maintain your current plan tier.",
                    "currentPlan", currentPlan != null ? currentPlan : "FREE",
                    "requestedPlan", plan
                ));
            }

            workspace.setSubscriptionPlan(plan);
            workspace.setUpdatedAt(LocalDateTime.now());

            switch (plan.toUpperCase()) {
                case "FREE":
                    workspace.setMaxMembers(3);
                    workspace.setMaxWorkspaces(3);
                    workspace.setCollaborationEnabled(false);
                    workspace.setBillingStatus("ACTIVE");
                    break;
                case "PRO":
                    workspace.setMaxMembers(10);
                    workspace.setMaxWorkspaces(10);
                    workspace.setCollaborationEnabled(true);
                    break;
                case "ENTERPRISE":
                    workspace.setMaxMembers(Integer.MAX_VALUE);
                    workspace.setMaxWorkspaces(Integer.MAX_VALUE);
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

    @PostMapping("/{workspaceId}/select")
    public ResponseEntity<?> selectWorkspace(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String username = user.getUsername();

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

            userRepository.findById(workspace.getOwnerId()).ifPresent(owner -> {
                userRepository.findById(owner.getId()).ifPresent(workspaceService::syncWorkspacesToOwnerPlan);
            });
            workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            workspace = workspaceOpt.get();

            if (user.getId().equals(workspace.getOwnerId())) {
                user = userRepository.findById(user.getId()).orElse(user);
            }

            Workspace.WorkspaceMember selfMember = workspace.getMember(user.getId());
            if (selfMember == null) {
                selfMember = workspace.getMemberByEmail(user.getEmail());
            }
            if (selfMember != null && selfMember.getStatus() == Workspace.MemberStatus.PENDING) {
                log.info("[Workspace] User {} has a pending invitation for workspace {} — requiring accept/reject",
                        user.getId(), workspaceId);
                return ResponseEntity.status(403).body(Map.of(
                    "error", "You have a pending invitation for this workspace. Please accept or decline it.",
                    "requiresInvitationAcceptance", true,
                    "workspaceId", workspaceId,
                    "workspaceName", workspace.getName(),
                    "invitationToken", selfMember.getInvitationToken() != null ? selfMember.getInvitationToken() : "",
                    "invitedEmail", selfMember.getEmail() != null ? selfMember.getEmail() : ""
                ));
            }

            WorkspaceRole role = workspaceService.getMemberRole(workspaceId, user.getId());

            if (role == null) {
                role = user.getId().equals(workspace.getOwnerId()) ? WorkspaceRole.OWNER : WorkspaceRole.MEMBER;
                log.warn("[Workspace] getMemberRole returned null for user {} in workspace {} — resolved to {}",
                    user.getId(), workspaceId, role);
            }
            String billingStatus = resolveBillingStatus(workspace);

            String workspacePlan = workspace.getSubscriptionPlan() != null
                    ? workspace.getSubscriptionPlan().toUpperCase()
                    : "FREE";

            boolean isPaidPlan = !"FREE".equalsIgnoreCase(workspacePlan);

            boolean hasValidBilling = "ACTIVE".equalsIgnoreCase(billingStatus)
                    || "TRIALING".equalsIgnoreCase(billingStatus)
                    || "PAST_DUE".equalsIgnoreCase(billingStatus)
                    || "PAYMENT_ACTION_REQUIRED".equalsIgnoreCase(billingStatus);

            User workspaceOwner = userRepository.findById(workspace.getOwnerId()).orElse(null);
            boolean ownerEnterpriseBypass = workspaceOwner != null
                    && systemSettingsService.isEnterpriseBypass(workspaceOwner.getEmail());
            boolean isEnterpriseDomainUser = systemSettingsService.isEnterpriseBypass(user.getEmail());
            if (isPaidPlan && !hasValidBilling && !isEnterpriseDomainUser && !ownerEnterpriseBypass) {
                log.warn("[Workspace] Access blocked for workspace {} due to {} billing status", workspaceId, billingStatus);
                return ResponseEntity.status(402).body(Map.of(
                    "error", "Plan validity has ended. Please update your subscription to restore access.",
                    "billingStatus", billingStatus,
                    "requiresPayment", true
                ));
            }

            boolean canUsePaidFeatures = Boolean.TRUE.equals(workspace.getCollaborationEnabled()) && hasValidBilling;
            if (ownerEnterpriseBypass) {
                canUsePaidFeatures = true;
            }
            String effectivePlan = canUsePaidFeatures ? workspacePlan : "FREE";
            if (ownerEnterpriseBypass) {
                effectivePlan = "ENTERPRISE";
            }

            Map<String, Object> claims = new HashMap<>();
            claims.put("workspaceId", workspaceId);
            claims.put("workspaceName", workspace.getName());
            claims.put("workspaceRole", role.toString());
            claims.put("userId", user.getId());
            claims.put("email", user.getEmail());
            claims.put("roles", user.getRoles());
            claims.put("isAdmin", user.getRoles().contains("ROLE_ADMIN"));
            claims.put("plan", effectivePlan);
            claims.put("subscriptionPlan", effectivePlan);
            claims.put("billingStatus", billingStatus);

            String token = jwtUtil.generateToken(email, claims);

            return ResponseEntity.ok(Map.of(
                "jwt", token,
                "username", username,
                "workspaceId", workspaceId,
                "workspaceName", workspace.getName(),
                "role", role.toString(),
                "subscriptionPlan", effectivePlan,
                "billingStatus", billingStatus
            ));
        } catch (Exception e) {
            log.error("Error selecting workspace", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{workspaceId}")
    public ResponseEntity<?> getWorkspace(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

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

    @PatchMapping("/{workspaceId}")
    public ResponseEntity<?> updateWorkspace(
            @PathVariable String workspaceId,
            @RequestBody Map<String, String> request) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            if (!workspace.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owner can update workspace settings"
                ));
            }

            String name = request.get("name");
            if (name != null && !name.isBlank()) {
                String trimmedName = name.trim();
                boolean duplicateName = workspaceService.getOwnedWorkspaces(user.getId()).stream()
                    .anyMatch(w -> !w.getWorkspaceId().equals(workspaceId)
                        && w.getName().equalsIgnoreCase(trimmedName));
                if (duplicateName) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "A workspace with this name already exists"
                    ));
                }
            }

            Workspace updatedWorkspace = workspaceService.updateWorkspace(
                workspaceId,
                name,
                request.get("description")
            );

            return ResponseEntity.ok(Map.of(
                "message", "Workspace updated successfully",
                "workspace", convertToDTO(updatedWorkspace)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating workspace {}", workspaceId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<?> deleteWorkspace(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();

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

            workspaceService.deleteWorkspace(workspaceId, user.getId());

            log.info("Workspace {} soft deleted by user {}", workspaceId, email);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace deleted successfully"
            ));
        } catch (Exception e) {
            log.error("Error deleting workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{workspaceId}/restore")
    public ResponseEntity<?> restoreWorkspace(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "true") boolean restoreProjects,
            @RequestParam(defaultValue = "true") boolean restoreFiles) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String username = user.getUsername();

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspaceIncludingDeleted(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();
            if (!workspace.getOwnerId().equals(user.getId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owner can restore the workspace"
                ));
            }

            if (!Boolean.TRUE.equals(workspace.getIsDeleted())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace is not deleted"));
            }

            workspaceService.restoreWorkspace(workspaceId, restoreProjects, restoreFiles);

            log.info("Workspace {} restored by user {}", workspaceId, username);

            return ResponseEntity.ok(Map.of(
                "message", "Workspace restored successfully",
                "workspaceId", workspaceId,
                "projectsRestored", restoreProjects,
                "filesRestored", restoreFiles
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error restoring workspace: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/deleted")
    public ResponseEntity<?> getDeletedWorkspaces() {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            List<Workspace> deletedWorkspaces = workspaceService.getDeletedUserWorkspaces(user.getId());

            return ResponseEntity.ok(deletedWorkspaces);
        } catch (Exception e) {
            log.error("Error getting deleted workspaces: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{workspaceId}/leave")
    public ResponseEntity<?> leaveWorkspace(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }
            User user = userOpt.get();

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            Workspace workspace = workspaceOpt.get();

            if (user.getId().equals(workspace.getOwnerId())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace owner cannot leave. Transfer ownership or delete the workspace."
                ));
            }
            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "You are not a member of this workspace"));
            }

            workspaceService.leaveWorkspace(workspaceId, user.getId());
            log.info("User {} left workspace {}", user.getId(), workspaceId);
            return ResponseEntity.ok(Map.of("message", "You have left the workspace", "workspaceId", workspaceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error leaving workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable String workspaceId,
            @PathVariable String userId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            String username = user.getUsername();

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }

            Workspace workspace = workspaceOpt.get();

            String targetUserId = userId;
            String targetEmail = null;
            if (userId.contains("@")) {
                targetEmail = userId;
                Optional<User> targetUserOpt = userRepository.findByEmail(userId);
                if (targetUserOpt.isPresent()) {
                    targetUserId = targetUserOpt.get().getId();
                }
            } else {

                Optional<User> targetUserOpt = userRepository.findById(userId);
                if (targetUserOpt.isPresent()) {
                    targetEmail = targetUserOpt.get().getEmail();
                }
            }

            Workspace.WorkspaceRole callerRole = workspaceService.getMemberRole(workspaceId, user.getId());
            boolean isOwner = callerRole == Workspace.WorkspaceRole.OWNER || workspace.getOwnerId().equals(user.getId());
            boolean isAdmin = callerRole == Workspace.WorkspaceRole.ADMIN;

            if (!isOwner && !isAdmin) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Only workspace owners and admins can remove members"
                ));
            }

            boolean isSelf = targetUserId.equals(user.getId())
                    || (targetEmail != null && targetEmail.equalsIgnoreCase(user.getEmail()));
            if (isSelf && (user.getId().equals(workspace.getOwnerId()) || targetUserId.equals(workspace.getOwnerId()))) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workspace owner cannot be removed. Please transfer ownership or delete the workspace."
                ));
            }

            if (isSelf) {
                workspaceService.leaveWorkspace(workspaceId, user.getId());
                return ResponseEntity.ok(Map.of(
                    "message", "You have left the workspace",
                    "workspaceId", workspaceId
                ));
            }

            if (targetUserId.equals(workspace.getOwnerId())) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Cannot remove the workspace owner"
                ));
            }

            if (isAdmin && !isOwner) {
                Workspace.WorkspaceRole targetRole = workspaceService.getMemberRole(workspaceId, targetUserId);
                if (targetRole == Workspace.WorkspaceRole.ADMIN || targetRole == Workspace.WorkspaceRole.OWNER) {
                    return ResponseEntity.status(403).body(Map.of(
                        "error", "Admins cannot remove other admins or the owner. Only the workspace owner can do this."
                    ));
                }
            }

            workspaceService.removeMember(workspaceId, userId);

            log.info("Member {} removed from workspace {} by {}", userId, workspaceId, username);

            return ResponseEntity.ok(Map.of(
                "message", "Member removed successfully"
            ));
        } catch (Exception e) {
            log.error("Error removing member from workspace", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{workspaceId}/members/{userId}/role")
    public ResponseEntity<?> updateMemberRole(
            @PathVariable String workspaceId,
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        try {
            String newRole = body.get("role");
            if (newRole == null || newRole.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "role is required"));
            }
            Workspace.WorkspaceRole role;
            try {
                role = Workspace.WorkspaceRole.valueOf(newRole.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid role. Must be OWNER, ADMIN, or MEMBER"));
            }

            String email = getCurrentUserEmail();
            Optional<User> callerOpt = userRepository.findByEmail(email);
            if (callerOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }
            User caller = callerOpt.get();
            String username = caller.getUsername();

            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            Workspace workspace = workspaceOpt.get();

            Workspace.WorkspaceMember callerMember = workspace.getMember(caller.getId());
            if (callerMember == null) {
                return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
            }
            boolean callerIsOwner = callerMember.getRole() == Workspace.WorkspaceRole.OWNER;
            boolean callerIsAdmin = callerMember.getRole() == Workspace.WorkspaceRole.ADMIN;
            if (!callerIsOwner && !callerIsAdmin) {
                return ResponseEntity.status(403).body(Map.of("error", "Only workspace owners and admins can change member roles"));
            }

            if (role == Workspace.WorkspaceRole.OWNER && !callerIsOwner) {
                return ResponseEntity.status(403).body(Map.of("error", "Only the workspace owner can transfer ownership"));
            }

            if (userId.equals(caller.getId()) && callerIsOwner) {
                return ResponseEntity.badRequest().body(Map.of("error", "Use the transfer ownership flow to change the owner's role"));
            }

            Workspace.WorkspaceMember target = workspace.getMember(userId);
            if (target == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Member not found in workspace"));
            }
            Workspace.WorkspaceRole previousRole = target.getRole();

            if (role == Workspace.WorkspaceRole.OWNER) {
                workspace.getMembers().stream()
                    .filter(m -> m.getRole() == Workspace.WorkspaceRole.OWNER)
                    .forEach(m -> m.setRole(Workspace.WorkspaceRole.ADMIN));
                workspace.setOwnerId(userId);
            }
            target.setRole(role);
            workspaceService.updateWorkspace(workspace);

            workspaceService.syncAdminRoleChangeToProjects(workspace, userId, previousRole, role);

            log.info("Member {} role changed to {} in workspace {} by {}", userId, role, workspaceId, username);
            return ResponseEntity.ok(Map.of("message", "Role updated successfully", "role", role.name()));
        } catch (Exception e) {
            log.error("Error updating member role", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String getCurrentUserEmail() {
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

        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
        dto.put("subscriptionPlan", plan);
        dto.put("billingStatus", resolveBillingStatus(workspace));
        dto.put("collaborationEnabled", workspace.getCollaborationEnabled());
        dto.put("collaborationLevel", getCollaborationLevel(plan));
        dto.put("hasBasicCollaboration", hasBasicCollaboration(plan));
        dto.put("hasAdvancedCollaboration", hasAdvancedCollaboration(plan));

        dto.put("createdAt", workspace.getCreatedAt());
        dto.put("updatedAt", workspace.getUpdatedAt());

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

    private String resolveBillingStatus(Workspace workspace) {
        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan)) {
            return "ACTIVE";
        }

        Optional<User> ownerOpt = userRepository.findById(workspace.getOwnerId());
        if (ownerOpt.isPresent() && systemSettingsService.isEnterpriseBypass(ownerOpt.get().getEmail())) {
            return "ACTIVE";
        }

        String stored = workspace.getBillingStatus();

        if ("ACTIVE".equalsIgnoreCase(stored) || "TRIALING".equalsIgnoreCase(stored)) {
            LocalDateTime periodEnd = workspace.getSubscriptionCurrentPeriodEnd();
            if (periodEnd != null && periodEnd.isBefore(LocalDateTime.now())) {

                workspace.setBillingStatus("EXPIRED");
                workspace.setCollaborationEnabled(false);
                try { workspaceService.updateWorkspace(workspace); } catch (Exception e) {
                    log.error("Failed to persist EXPIRED billing status for workspace {}: {}",
                            workspace.getWorkspaceId(), e.getMessage());
                }
                log.warn("Workspace {} subscription period ended ({}), auto-set to EXPIRED",
                    workspace.getWorkspaceId(), periodEnd);
                return "EXPIRED";
            }
        }

        if (stored != null && !stored.isBlank()) {
            return stored;
        }

        return Boolean.TRUE.equals(workspace.getCollaborationEnabled()) ? "ACTIVE" : "PENDING";
    }

    private int getMaxMembersForPlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 3;
            case "PRO" -> 10;
            case "ENTERPRISE" -> Integer.MAX_VALUE;
            default -> 3;
        };
    }

    private int getMaxWorkspacesForPlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 3;
            case "PRO" -> 10;
            case "ENTERPRISE" -> Integer.MAX_VALUE;
            default -> 3;
        };
    }

    private boolean isDowngrade(String currentPlan, String newPlan) {
        if (currentPlan == null) return false;
        int currentRank = getPlanRank(currentPlan);
        int newRank = getPlanRank(newPlan);
        return newRank < currentRank;
    }

    private int getPlanRank(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 1;
            case "PRO" -> 2;
            case "ENTERPRISE" -> 3;
            default -> 0;
        };
    }

    private boolean isUpgrade(String currentPlan, String newPlan) {
        if (currentPlan == null) currentPlan = "FREE";
        return getPlanRank(newPlan) > getPlanRank(currentPlan);
    }

    private int planTier(String plan) {
        return getPlanRank(plan);
    }

    private boolean hasBasicCollaboration(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> false;
            case "PRO" -> true;
            case "ENTERPRISE" -> true;
            default -> false;
        };
    }

    private boolean hasAdvancedCollaboration(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> false;
            case "PRO" -> false;
            case "ENTERPRISE" -> true;
            default -> false;
        };
    }

    public String getCollaborationLevel(String plan) {
        if (hasAdvancedCollaboration(plan)) {
            return "advanced";
        } else if (hasBasicCollaboration(plan)) {
            return "basic";
        } else {
            return "none";
        }
    }
}
