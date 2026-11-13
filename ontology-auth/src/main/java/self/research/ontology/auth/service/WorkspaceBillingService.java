package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.dto.WorkspaceRequests.UpdateSubscriptionRequest;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.model.Workspace.WorkspaceRole;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.util.CurrentUserEmail;
import self.research.ontology.auth.util.WorkspaceErrors;
import self.research.ontology.auth.util.JwtUtil;
import self.research.ontology.auth.util.WorkspaceCaller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkspaceBillingService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBillingService.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final SystemSettingsService systemSettingsService;
    private final WorkspaceBillingStatusResolver billingStatusResolver;
    private final WorkspaceDtoMapper dtoMapper;

    public WorkspaceBillingService(WorkspaceService workspaceService,
                                   UserRepository userRepository,
                                   JwtUtil jwtUtil,
                                   SystemSettingsService systemSettingsService,
                                   WorkspaceBillingStatusResolver billingStatusResolver,
                                   WorkspaceDtoMapper dtoMapper) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.systemSettingsService = systemSettingsService;
        this.billingStatusResolver = billingStatusResolver;
        this.dtoMapper = dtoMapper;
    }

    private WorkspaceCaller resolveWithAccess(String workspaceId) {
        return WorkspaceCaller.resolve(userRepository,
                user -> workspaceService.hasAccess(workspaceId, user.getId()),
                () -> workspaceService.getWorkspace(workspaceId));
    }

    public ResponseEntity<?> getSubscription(String workspaceId) {
        try {
            WorkspaceCaller caller = resolveWithAccess(workspaceId);
            if (caller.failed()) {
                return caller.failure();
            }
            Workspace workspace = caller.workspace();

            String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";

            Map<String, Object> subscription = new HashMap<>();
            subscription.put("plan", plan);
            subscription.put("maxMembers", workspace.getMaxMembers());
            subscription.put("maxWorkspaces", workspace.getMaxWorkspaces());
            subscription.put("collaborationEnabled", workspace.getCollaborationEnabled());
            subscription.put("collaborationLevel", WorkspacePlans.collaborationLevel(plan));
            subscription.put("hasBasicCollaboration", WorkspacePlans.hasBasicCollaboration(plan));
            subscription.put("hasAdvancedCollaboration", WorkspacePlans.hasAdvancedCollaboration(plan));
            subscription.put("startDate", workspace.getSubscriptionStartDate());
            subscription.put("endDate", workspace.getSubscriptionEndDate());

            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            log.error("Error getting subscription details", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    public ResponseEntity<?> updateSubscription(String workspaceId, UpdateSubscriptionRequest request) {
        try {
            WorkspaceCaller caller = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId))
                    .requireOwner("Only workspace owner can update subscription plan");
            if (caller.failed()) {
                return caller.failure();
            }
            User user = caller.user();
            String username = user.getUsername();
            Workspace workspace = caller.workspace();

            String plan = request.getSubscriptionPlan();
            String currentPlan = workspace.getSubscriptionPlan();

            if (WorkspacePlans.isUpgrade(currentPlan, plan)) {
                String stripeStatus = user.getSubscriptionStatus();
                String stripePlan  = user.getSubscriptionPlanName();
                boolean hasActiveStripeSub =
                        user.getStripeSubscriptionId() != null
                        && ("active".equalsIgnoreCase(stripeStatus) || "trialing".equalsIgnoreCase(stripeStatus))
                        && stripePlan != null
                        && WorkspacePlans.planRank(stripePlan) >= WorkspacePlans.planRank(plan);

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

            if (WorkspacePlans.isDowngrade(currentPlan, plan)) {
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
                "workspace", dtoMapper.toDto(updatedWorkspace)
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

    public ResponseEntity<?> selectWorkspace(String workspaceId) {
        try {
            WorkspaceCaller caller = resolveWithAccess(workspaceId);
            if (caller.failed()) {
                return caller.failure();
            }
            User user = caller.user();
            String username = user.getUsername();
            Workspace workspace = caller.workspace();

            userRepository.findById(workspace.getOwnerId()).ifPresent(owner -> {
                userRepository.findById(owner.getId()).ifPresent(workspaceService::syncWorkspacesToOwnerPlan);
            });
            Optional<Workspace> workspaceOpt = workspaceService.getWorkspace(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return WorkspaceErrors.workspaceNotFound();
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
            String billingStatus = billingStatusResolver.resolve(workspace);

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

            String token = jwtUtil.generateToken(CurrentUserEmail.get(), claims);

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
}
