package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.InvitationRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.InvitationService;
import self.research.ontology.auth.service.SystemSettingsService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    private static final Logger log = LoggerFactory.getLogger(InvitationController.class);

    private final InvitationService invitationService;
    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.base-url:http://localhost:8082}")
    private String baseUrl;

    public InvitationController(InvitationService invitationService,
                               InvitationRepository invitationRepository,
                               UserRepository userRepository,
                               WorkspaceRepository workspaceRepository,
                               ProjectRepository projectRepository,
                               SystemSettingsService systemSettingsService) {
        this.invitationService = invitationService;
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.projectRepository = projectRepository;
        this.systemSettingsService = systemSettingsService;
    }

    /**
     * Get current authenticated username
     */
    private String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }

    /**
     * Send invitation to a user
     */
    @PostMapping("/send")
    public ResponseEntity<?> sendInvitation(@Valid @RequestBody SendInvitationRequest request) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify workspace access
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(request.workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            Workspace.WorkspaceMember wsMember = workspace.getMember(user.getId());
            if (wsMember == null) {
                return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
            }
            // Only workspace OWNER or ADMIN can send invitations
            if (wsMember.getRole() != Workspace.WorkspaceRole.OWNER && wsMember.getRole() != Workspace.WorkspaceRole.ADMIN) {
                return ResponseEntity.status(403).body(Map.of("error", "Only workspace owners and admins can invite members"));
            }

            // ── Plan enforcement: collaboration gate ─────────────────────────
            // FREE plan allows up to 3 members — seat limit enforced below.
            // Paid plans require collaborationEnabled=true (set on subscription activation).
            // Enterprise domain bypass owners are always allowed to collaborate.
            boolean ownerIsEnterpriseDomain = userRepository.findById(workspace.getOwnerId())
                .map(o -> systemSettingsService.isEnterpriseBypass(o.getEmail()))
                .orElse(false);
            if (!ownerIsEnterpriseDomain && !Boolean.TRUE.equals(workspace.getCollaborationEnabled())) {
                String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
                if (!"FREE".equalsIgnoreCase(plan)) {
                    return ResponseEntity.status(402).body(Map.of(
                        "error", "Collaboration is not yet activated for this workspace. Please complete your subscription payment first.",
                        "requiresPayment", true
                    ));
                }
                // FREE plan: fall through to seat-limit check (3-member cap)
            }

            // ── Plan enforcement: member seat limit ───────────────────────────
            // Count both ACTIVE and PENDING to prevent over-inviting before accepts
            long usedSeats = workspace.getMembers().stream()
                .filter(m -> m.getStatus() == Workspace.MemberStatus.ACTIVE
                          || m.getStatus() == Workspace.MemberStatus.PENDING)
                .count();
            int maxSeats = maxMembersForPlan(workspace);
            if (usedSeats >= maxSeats) {
                return ResponseEntity.status(402).body(Map.of(
                    "error", "Member limit reached (" + maxSeats + "/" + maxSeats + " seats used). Upgrade your plan to add more members.",
                    "requiresUpgrade", true,
                    "currentCount", usedSeats,
                    "maxAllowed", maxSeats
                ));
            }

            // Check if user is already an active or pending member
            Workspace.WorkspaceMember existingMember = workspace.getMemberByEmail(request.email);
            if (existingMember != null) {
                if (existingMember.getStatus() == Workspace.MemberStatus.ACTIVE) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "This user is already a member of this workspace",
                        "success", false
                    ));
                }
                if (existingMember.getStatus() == Workspace.MemberStatus.PENDING) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "An invitation has already been sent to this email address. Use 'Resend Invitation' if they haven't received it.",
                        "success", false,
                        "alreadyPending", true
                    ));
                }
            }
            
            Invitation invitation = invitationService.createInvitation(
                request.workspaceId,
                request.email,
                request.role,
                user.getUsername(),
                user.getEmail()
            );
            
            // Add pending member to workspace
            workspace.addPendingMember(
                request.email, 
                Workspace.WorkspaceRole.valueOf(request.role),
                invitation.getInvitationToken()
            );
            workspaceRepository.save(workspace);
            log.info("Added pending member {} to workspace {}", request.email, workspace.getWorkspaceId());

            return ResponseEntity.ok(Map.of(
                "message", "Invitation sent successfully. The user will receive an email with instructions.",
                "invitation", convertToDTO(invitation),
                "workspace", convertWorkspaceToDTO(workspace),
                "success", true
            ));
        } catch (IllegalArgumentException e) {
            log.error("Error sending invitation", e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "success", false
            ));
        } catch (Exception e) {
            log.error("Error sending invitation", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to send invitation. Please check server logs for details.",
                "success", false
            ));
        }
    }

    /**
     * Get invitation details by token (public endpoint)
     */
    @GetMapping("/details/{token}")
    public ResponseEntity<?> getInvitationDetails(@PathVariable String token) {
        try {
            Optional<Invitation> invitationOpt = invitationService.getInvitationByToken(token);
            
            if (invitationOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Invitation invitation = invitationOpt.get();
            
            // Return invitation details with status information
            // Frontend can decide what to show based on status
            Map<String, Object> response = new HashMap<>();
            response.put("invitation", convertToDTO(invitation));
            
            if (invitation.isExpired()) {
                response.put("expired", true);
                response.put("message", "This invitation has expired");
            } else if ("ACCEPTED".equals(invitation.getStatus())) {
                response.put("alreadyAccepted", true);
                response.put("message", "This invitation has already been accepted");
            } else if ("CANCELLED".equals(invitation.getStatus())) {
                response.put("cancelled", true);
                response.put("message", "This invitation has been cancelled");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching invitation details", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch invitation"));
        }
    }

    /**
     * Accept invitation
     */
    @PostMapping("/accept/{token}")
    public ResponseEntity<?> acceptInvitation(@PathVariable String token) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Get invitation details
            Optional<Invitation> invitationOpt = invitationService.getInvitationByToken(token);
            if (invitationOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invitation not found"));
            }
            
            Invitation invitation = invitationOpt.get();
            
            // Check if invitation is still pending
            if (!"PENDING".equals(invitation.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invitation has already been " + invitation.getStatus().toLowerCase()));
            }
            
            // Add user to workspace
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(invitation.getWorkspaceId());
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            
            // First check by invitation email (the email that was invited)
            Workspace.WorkspaceMember existingMember = workspace.getMemberByEmail(invitation.getInviteeEmail());
            
            // Also check by user's actual email (in case they differ)
            if (existingMember == null) {
                existingMember = workspace.getMemberByEmail(user.getEmail());
            }
            
            // Check if user is already an active member - treat as success case
            if (existingMember != null && existingMember.getStatus() == Workspace.MemberStatus.ACTIVE) {
                log.info("User {} is already an active member of workspace {}", user.getUsername(), workspace.getWorkspaceId());
                
                // Mark invitation as accepted if it's still pending
                if ("PENDING".equals(invitation.getStatus())) {
                    invitationService.acceptInvitation(token, user.getId());
                }
                
                return ResponseEntity.ok(Map.of(
                    "message", "You are already a member of this workspace",
                    "invitation", convertToDTO(invitation),
                    "workspaceId", invitation.getWorkspaceId(),
                    "workspaceName", invitation.getWorkspaceName(),
                    "workspace", convertWorkspaceToDTO(workspace),
                    "needsRefresh", true,
                    "alreadyMember", true
                ));
            }
            
            // Re-validate member limit before activating (guards against concurrent accepts filling the workspace)
            if (existingMember == null || existingMember.getStatus() != Workspace.MemberStatus.PENDING) {
                long activeCount = workspace.getMembers().stream()
                    .filter(m -> m.getStatus() == Workspace.MemberStatus.ACTIVE).count();
                if (activeCount >= maxMembersForPlan(workspace)) {
                    return ResponseEntity.status(402).body(Map.of(
                        "error", "This workspace has reached its member limit. Please ask the workspace owner to upgrade.",
                        "requiresUpgrade", true
                    ));
                }
            }

            // Activate pending member or add new member
            if (existingMember != null && existingMember.getStatus() == Workspace.MemberStatus.PENDING) {
                // Activate the pending member - change status from PENDING to ACTIVE
                // Use the invitation email to find the correct pending member
                workspace.activatePendingMember(invitation.getInviteeEmail(), user.getId(), user.getUsername());
                log.info("Activated pending member {} (invitation email: {}) in workspace {}", 
                    user.getUsername(), invitation.getInviteeEmail(), workspace.getWorkspaceId());
            } else {
                // Add as new member (this case shouldn't normally happen, but handle it)
                workspace.addMember(user.getId(), user.getUsername(), user.getEmail(), Workspace.WorkspaceRole.valueOf(invitation.getRole()));
                log.info("Added user {} to workspace {}", user.getEmail(), workspace.getWorkspaceId());
            }
            workspaceRepository.save(workspace);
            
            // Accept the invitation (marks it as ACCEPTED)
            Invitation acceptedInvitation = invitationService.acceptInvitation(token, user.getId());

            // Auto-add new member to workspace projects based on the invited role and project visibility:
            //   ADMIN  → added to every non-private project (WORKSPACE or SPECIFIC, or legacy shared)
            //   VIEWER → added only to WORKSPACE projects (created with "all workspace members")
            // Private projects (visibility=PRIVATE or legacy single-owner) are never exposed.
            try {
                boolean isNewAdmin = "ADMIN".equalsIgnoreCase(invitation.getRole());
                List<Project> workspaceProjects = projectRepository.findActiveByWorkspaceId(workspace.getWorkspaceId());
                for (Project project : workspaceProjects) {
                    boolean alreadyMember = project.getMembers().stream()
                            .anyMatch(m -> user.getId().equals(m.getUserId()));
                    boolean isOwner = user.getId().equals(project.getOwnerId());
                    if (alreadyMember || isOwner) continue;

                    String visibility = project.getVisibility();
                    boolean isPrivate = "PRIVATE".equals(visibility)
                            || (visibility == null && (project.getMembers() == null || project.getMembers().size() <= 1));
                    if (isPrivate) continue;

                    // Admins get added to all non-private projects; regular members only to WORKSPACE ones
                    boolean shouldAdd = isNewAdmin || "WORKSPACE".equals(visibility);
                    if (shouldAdd) {
                        String autoRole = isNewAdmin ? "EDITOR" : "VIEWER";
                        project.addMember(user.getId(), user.getUsername(), user.getEmail(), autoRole);
                        projectRepository.save(project);
                        log.info("Auto-added {} as {} to project {} (visibility={}) in workspace {}",
                                user.getUsername(), autoRole, project.getName(), visibility, workspace.getWorkspaceId());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to auto-add user {} to workspace projects: {}", user.getUsername(), e.getMessage());
            }

            return ResponseEntity.ok(Map.of(
                "message", "Invitation accepted successfully",
                "invitation", convertToDTO(acceptedInvitation),
                "workspaceId", acceptedInvitation.getWorkspaceId(),
                "workspaceName", acceptedInvitation.getWorkspaceName(),
                "workspace", convertWorkspaceToDTO(workspace),
                "needsRefresh", true
            ));
        } catch (IllegalArgumentException e) {
            log.error("Error accepting invitation", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error accepting invitation", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to accept invitation"));
        }
    }

    /**
     * Get pending invitations for a workspace
     */
    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<?> getWorkspaceInvitations(@PathVariable String workspaceId) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify workspace access
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            if (!workspace.isMember(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            
            Iterable<Invitation> invitations = invitationService.getPendingInvitations(workspaceId);
            
            List<Map<String, Object>> invitationDTOs = StreamSupport
                .stream(invitations.spliterator(), false)
                .map(this::convertToDTO)
                .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                "invitations", invitationDTOs,
                "count", invitationDTOs.size()
            ));
        } catch (Exception e) {
            log.error("Error fetching workspace invitations", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch invitations"));
        }
    }

    /**
     * Resend invitation (for expired invitations)
     */
    @PostMapping("/resend")
    public ResponseEntity<?> resendInvitation(@Valid @RequestBody ResendInvitationRequest request) {
        try {
            log.info("Resending invitation to {} for workspace {}", request.email, request.workspaceId);
            
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            User user = userOpt.get();
            
            // Verify workspace access
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(request.workspaceId);
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            if (!workspace.isMember(user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }
            
            // Find existing invitation
            Optional<Invitation> existingInvitation = invitationService.findByEmailAndWorkspace(request.email, request.workspaceId);
            
            if (existingInvitation.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No invitation found for this email"));
            }
            
            Invitation invitation = existingInvitation.get();
            
            // Extend expiry and reset status — keep the same token so old email links stay valid.
            // Generating a new token on every resend would invalidate any previously-sent links
            // and cause "Invalid Invitation" errors for recipients who already opened the old email.
            invitation.setExpiresAt(LocalDateTime.now().plusDays(7));
            invitation.setStatus("PENDING");
            invitation = invitationRepository.save(invitation);

            // Resend the email with the same (now re-valid) token
            invitationService.sendInvitationEmail(invitation, workspace);
            
            log.info("Invitation resent successfully to {}", request.email);
            
            return ResponseEntity.ok(Map.of(
                "message", "Invitation resent successfully",
                "invitation", convertToDTO(invitation)
            ));
        } catch (Exception e) {
            log.error("Error resending invitation", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to resend invitation"));
        }
    }

    /**
     * Cancel invitation
     */
    @DeleteMapping("/{token}")
    public ResponseEntity<?> cancelInvitation(@PathVariable String token) {
        try {
            String email = getCurrentUserEmail();
            Optional<User> userOpt = userRepository.findByEmail(email);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }
            
            // Get invitation to find workspace
            Optional<Invitation> invitationOpt = invitationService.getInvitationByToken(token);
            if (invitationOpt.isPresent()) {
                Invitation invitation = invitationOpt.get();
                
                // Remove pending member from workspace
                Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(invitation.getWorkspaceId());
                if (workspaceOpt.isPresent()) {
                    Workspace workspace = workspaceOpt.get();
                    workspace.cancelPendingMember(token);
                    workspaceRepository.save(workspace);
                    log.info("Removed pending member for token {} from workspace {}", token.substring(0, Math.min(10, token.length())), workspace.getWorkspaceId());
                }
            }

            invitationService.cancelInvitation(token);

            return ResponseEntity.ok(Map.of("message", "Invitation cancelled successfully"));
        } catch (Exception e) {
            log.error("Error cancelling invitation", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to cancel invitation"));
        }
    }

    /**
     * Request invitation resend (public endpoint - called by invitee when invitation expired)
     * This allows the invitee to request a new invitation link without being authenticated
     */
    @PostMapping("/request-resend/{token}")
    public ResponseEntity<?> requestInvitationResend(@PathVariable String token) {
        try {
            log.info("Requesting invitation resend for token: {}", token.substring(0, Math.min(10, token.length())) + "...");
            
            // Find the invitation by token
            Optional<Invitation> invitationOpt = invitationService.getInvitationByToken(token);
            
            if (invitationOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invitation not found"));
            }
            
            Invitation invitation = invitationOpt.get();
            
            // Only allow resend for expired or pending invitations
            if ("ACCEPTED".equals(invitation.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "This invitation has already been accepted"));
            }
            
            if ("CANCELLED".equals(invitation.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "This invitation has been cancelled. Please contact the workspace owner for a new invitation."));
            }
            
            // Get workspace to send email
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(invitation.getWorkspaceId());
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            
            // Generate new token and extend expiry
            String newToken = UUID.randomUUID().toString().replace("-", "");
            invitation.setInvitationToken(newToken);
            invitation.setExpiresAt(java.time.LocalDateTime.now().plusDays(7));
            invitation.setStatus("PENDING");
            invitationService.saveInvitation(invitation);
            
            // Send new invitation email
            invitationService.sendInvitationEmail(invitation, workspace);
            
            log.info("Invitation resend requested successfully for: {}", invitation.getInviteeEmail());
            
            return ResponseEntity.ok(Map.of(
                "message", "A new invitation has been sent to your email",
                "email", invitation.getInviteeEmail()
            ));
        } catch (Exception e) {
            log.error("Error requesting invitation resend", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to resend invitation. Please try again later."));
        }
    }

    /**
     * Convert Invitation to DTO
     */
    private Map<String, Object> convertToDTO(Invitation invitation) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", invitation.getId());
        dto.put("invitationToken", invitation.getInvitationToken());
        dto.put("inviteeEmail", invitation.getInviteeEmail());
        dto.put("workspaceId", invitation.getWorkspaceId());
        dto.put("workspaceName", invitation.getWorkspaceName());
        dto.put("invitedBy", invitation.getInvitedBy());
        dto.put("invitedByEmail", invitation.getInvitedByEmail());
        dto.put("role", invitation.getRole());
        dto.put("status", invitation.getStatus());
        dto.put("createdAt", invitation.getCreatedAt().toString());
        dto.put("expiresAt", invitation.getExpiresAt().toString());
        if (invitation.getAcceptedAt() != null) {
            dto.put("acceptedAt", invitation.getAcceptedAt().toString());
        }
        
        // Add invitation links
        String webLink = baseUrl + "/invite?token=" + invitation.getInvitationToken();
        String vscodeLink = "vscode://self.ontocode-extension/invite?token=" + invitation.getInvitationToken();
        dto.put("invitationLink", webLink);
        dto.put("webLink", webLink);
        dto.put("vscodeLink", vscodeLink);
        
        return dto;
    }

    /**
     * Convert Workspace to DTO
     */
    private Map<String, Object> convertWorkspaceToDTO(Workspace workspace) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("workspaceId", workspace.getWorkspaceId());
        dto.put("name", workspace.getName());
        dto.put("description", workspace.getDescription());
        dto.put("ownerId", workspace.getOwnerId());
        dto.put("memberCount", workspace.getMembers().size());
        dto.put("subscriptionPlan", workspace.getSubscriptionPlan());
        dto.put("collaborationEnabled", workspace.getCollaborationEnabled());
        return dto;
    }

    /** Returns the effective member seat cap for a workspace, with plan fallback. */
    private int maxMembersForPlan(Workspace workspace) {
        if (workspace.getMaxMembers() != null && workspace.getMaxMembers() > 0) {
            return workspace.getMaxMembers();
        }
        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";
        return switch (plan.toUpperCase()) {
            case "PRO" -> 10;
            case "ENTERPRISE" -> Integer.MAX_VALUE;
            default -> 3; // FREE
        };
    }

    // Request DTOs
    public static class SendInvitationRequest {
        public String workspaceId;
        public String email;
        public String role;
    }
    
    public static class ResendInvitationRequest {
        public String workspaceId;
        public String email;
    }
}
