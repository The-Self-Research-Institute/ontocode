package self.research.ontology.auth.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.InvitationService;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    private static final Logger log = LoggerFactory.getLogger(InvitationController.class);

    private final InvitationService invitationService;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;

    public InvitationController(InvitationService invitationService,
                               UserRepository userRepository,
                               WorkspaceRepository workspaceRepository) {
        this.invitationService = invitationService;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Get current authenticated username
     */
    private String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }

    /**
     * Send invitation to a user
     */
    @PostMapping("/send")
    public ResponseEntity<?> sendInvitation(@Valid @RequestBody SendInvitationRequest request) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
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
                return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
            }
            
            Invitation invitation = invitationService.createInvitation(
                request.workspaceId,
                request.email,
                request.role,
                user.getUsername(),
                user.getEmail()
            );

            return ResponseEntity.ok(Map.of(
                "message", "Invitation sent successfully. The user will receive an email with instructions.",
                "invitation", convertToDTO(invitation),
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
            
            if (invitation.isExpired()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invitation has expired"));
            }
            
            if (!"PENDING".equals(invitation.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invitation is no longer valid"));
            }

            return ResponseEntity.ok(Map.of("invitation", convertToDTO(invitation)));
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
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
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
            
            // Add user to workspace
            Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(invitation.getWorkspaceId());
            if (workspaceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Workspace not found"));
            }
            
            Workspace workspace = workspaceOpt.get();
            
            // Add member to workspace if not already a member
            if (!workspace.isMember(user.getId())) {
                workspace.addMember(user.getId(), user.getUsername(), user.getEmail(), Workspace.WorkspaceRole.valueOf(invitation.getRole()));
                workspaceRepository.save(workspace);
            }
            
            // Accept the invitation
            Invitation acceptedInvitation = invitationService.acceptInvitation(token, user.getId());

            return ResponseEntity.ok(Map.of(
                "message", "Invitation accepted successfully",
                "invitation", convertToDTO(acceptedInvitation),
                "workspaceId", acceptedInvitation.getWorkspaceId(),
                "workspaceName", acceptedInvitation.getWorkspaceName()
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
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
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
     * Cancel invitation
     */
    @DeleteMapping("/{token}")
    public ResponseEntity<?> cancelInvitation(@PathVariable String token) {
        try {
            String username = getCurrentUsername();
            Optional<User> userOpt = userRepository.findByUsername(username);
            
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
            }

            invitationService.cancelInvitation(token);

            return ResponseEntity.ok(Map.of("message", "Invitation cancelled successfully"));
        } catch (Exception e) {
            log.error("Error cancelling invitation", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to cancel invitation"));
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
        return dto;
    }

    // Request DTOs
    public static class SendInvitationRequest {
        public String workspaceId;
        public String email;
        public String role;
    }
}
