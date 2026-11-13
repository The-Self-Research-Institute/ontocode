package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.OwnershipTransferBilling;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.CurrentUserEmail;
import self.research.ontology.auth.util.WorkspaceCaller;
import self.research.ontology.auth.util.WorkspaceErrors;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceMemberController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemberController.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final SystemSettingsService systemSettingsService;

    public WorkspaceMemberController(WorkspaceService workspaceService,
                                     UserRepository userRepository,
                                     SystemSettingsService systemSettingsService) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.systemSettingsService = systemSettingsService;
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ResponseEntity<?> removeMember(
            @PathVariable String workspaceId,
            @PathVariable String userId) {
        try {
            WorkspaceCaller resolved = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId));
            if (resolved.failed()) {
                return resolved.failure();
            }
            User user = resolved.user();
            String username = user.getUsername();
            Workspace workspace = resolved.workspace();

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
                return WorkspaceErrors.forbidden("Only workspace owners and admins can remove members");
            }

            boolean isSelf = targetUserId.equals(user.getId())
                    || (targetEmail != null && targetEmail.equalsIgnoreCase(user.getEmail()));
            if (targetUserId.equals(workspace.getOwnerId())) {
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
                return WorkspaceErrors.forbidden("Cannot remove the workspace owner");
            }

            if (isAdmin && !isOwner) {
                Workspace.WorkspaceRole targetRole = workspaceService.getMemberRole(workspaceId, targetUserId);
                if (targetRole == Workspace.WorkspaceRole.ADMIN || targetRole == Workspace.WorkspaceRole.OWNER) {
                    return WorkspaceErrors.forbidden("Admins cannot remove other admins or the owner. Only the workspace owner can do this.");
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

            WorkspaceCaller resolved = WorkspaceCaller.resolve(userRepository,
                    () -> workspaceService.getWorkspace(workspaceId));
            if (resolved.failed()) {
                return resolved.failure();
            }
            User caller = resolved.user();
            String username = caller.getUsername();
            Workspace workspace = resolved.workspace();

            Workspace.WorkspaceMember callerMember = workspace.getMember(caller.getId());
            if (callerMember == null) {
                return WorkspaceErrors.noWorkspaceAccess();
            }
            boolean callerIsOwner = callerMember.getRole() == Workspace.WorkspaceRole.OWNER;
            boolean callerIsAdmin = callerMember.getRole() == Workspace.WorkspaceRole.ADMIN;
            if (!callerIsOwner && !callerIsAdmin) {
                return WorkspaceErrors.forbidden("Only workspace owners and admins can change member roles");
            }
            if (role == Workspace.WorkspaceRole.OWNER && !callerIsOwner) {
                return WorkspaceErrors.forbidden("Only the workspace owner can transfer ownership");
            }
            if (userId.equals(caller.getId()) && callerIsOwner) {
                return ResponseEntity.badRequest().body(Map.of("error", "Use the transfer ownership flow to change the owner's role"));
            }

            Workspace.WorkspaceMember target = workspace.getMember(userId);
            if (target == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Member not found in workspace"));
            }
            if (target.getRole() == Workspace.WorkspaceRole.OWNER && !callerIsOwner) {
                return WorkspaceErrors.forbidden("Only the workspace owner can change the owner's role");
            }
            if (role == Workspace.WorkspaceRole.OWNER) {
                Optional<String> billingBlock = userRepository.findById(userId).flatMap(targetUser ->
                        OwnershipTransferBilling.blockedReason(
                                workspace.getSubscriptionPlan(),
                                systemSettingsService.isEnterpriseBypass(targetUser.getEmail()),
                                targetUser.getUsername(),
                                targetUser.getSubscriptionPlanName(),
                                targetUser.getSubscriptionStatus(),
                                targetUser.getSubscriptionCurrentPeriodEnd(),
                                LocalDateTime.now()));
                if (billingBlock.isPresent()) {
                    log.warn("Ownership transfer of workspace {} to {} blocked: {}", workspaceId, userId, billingBlock.get());
                    return ResponseEntity.status(409).body(Map.of("error", billingBlock.get(), "requiresPayment", true));
                }
                workspaceService.transferOwnership(workspaceId, workspace.getOwnerId(), userId);
            } else {
                Workspace.WorkspaceRole previousRole = target.getRole();
                target.setRole(role);
                workspaceService.updateWorkspace(workspace);
                workspaceService.syncAdminRoleChangeToProjects(workspace, userId, previousRole, role);
            }

            log.info("Member {} role changed to {} in workspace {} by {}", userId, role, workspaceId, username);
            return ResponseEntity.ok(Map.of("message", "Role updated successfully", "role", role.name()));
        } catch (Exception e) {
            log.error("Error updating member role", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
