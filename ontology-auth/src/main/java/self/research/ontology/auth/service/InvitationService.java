package self.research.ontology.auth.service;

import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.InvitationRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationRepository invitationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EmailService emailService;

    public InvitationService(InvitationRepository invitationRepository,
                            WorkspaceRepository workspaceRepository,
                            EmailService emailService) {
        this.invitationRepository = invitationRepository;
        this.workspaceRepository = workspaceRepository;
        this.emailService = emailService;
    }

    /**
     * Create and send invitation to a user
     */
    public Invitation createInvitation(String workspaceId, String inviteeEmail, String role, 
                                      String invitedBy, String invitedByEmail) {
        // Verify workspace exists
        Optional<Workspace> workspaceOpt = workspaceRepository.findByWorkspaceId(workspaceId);
        if (workspaceOpt.isEmpty()) {
            throw new IllegalArgumentException("Workspace not found");
        }
        
        Workspace workspace = workspaceOpt.get();
        
        // Check if invitation already exists
        Optional<Invitation> existingInvitation = invitationRepository
            .findByInviteeEmailAndWorkspaceIdAndStatus(inviteeEmail, workspaceId, "PENDING");
        
        Invitation invitation;
        
        if (existingInvitation.isPresent()) {
            // Resend existing invitation instead of creating a new one
            invitation = existingInvitation.get();
            log.info("Found existing pending invitation for: {}. Resending...", inviteeEmail);
            
            // Update invitation timestamp and generate new token
            invitation.setInvitationToken(generateInvitationToken());
            invitation.setCreatedAt(java.time.LocalDateTime.now());
            invitation.setExpiresAt(java.time.LocalDateTime.now().plusDays(7));
            invitation = invitationRepository.save(invitation);
        } else {
            // Create new invitation
            invitation = new Invitation();
            invitation.setInvitationToken(generateInvitationToken());
            invitation.setInviteeEmail(inviteeEmail);
            invitation.setWorkspaceId(workspaceId);
            invitation.setWorkspaceName(workspace.getName());
            invitation.setInvitedBy(invitedBy);
            invitation.setInvitedByEmail(invitedByEmail);
            invitation.setRole(role);
            
            invitation = invitationRepository.save(invitation);
            log.info("Created new invitation for: {}", inviteeEmail);
        }
        
        // Send invitation email
        try {
            emailService.sendInvitationEmail(invitation);
            log.info("Invitation email sent successfully to: {}", inviteeEmail);
        } catch (Exception e) {
            log.error("Failed to send invitation email to: {}", inviteeEmail, e);
            log.error("Email service error details: ", e);
            // Don't fail the invitation creation if email fails, but log it
        }
        
        return invitation;
    }

    /**
     * Get invitation by token
     */
    public Optional<Invitation> getInvitationByToken(String token) {
        return invitationRepository.findByInvitationToken(token);
    }

    /**
     * Accept invitation
     */
    public Invitation acceptInvitation(String token, String userId) {
        Optional<Invitation> invitationOpt = invitationRepository.findByInvitationToken(token);
        
        if (invitationOpt.isEmpty()) {
            throw new IllegalArgumentException("Invitation not found");
        }
        
        Invitation invitation = invitationOpt.get();
        
        if (!invitation.isPending()) {
            throw new IllegalArgumentException("Invitation is not pending");
        }
        
        if (invitation.isExpired()) {
            invitation.setStatus("EXPIRED");
            invitationRepository.save(invitation);
            throw new IllegalArgumentException("Invitation has expired");
        }
        
        // Update invitation status
        invitation.setStatus("ACCEPTED");
        invitation.setAcceptedAt(LocalDateTime.now());
        
        return invitationRepository.save(invitation);
    }

    /**
     * Generate unique invitation token
     */
    private String generateInvitationToken() {
        String token;
        do {
            token = UUID.randomUUID().toString().replace("-", "");
        } while (invitationRepository.existsByInvitationToken(token));
        return token;
    }

    /**
     * Get pending invitations for workspace
     */
    public Iterable<Invitation> getPendingInvitations(String workspaceId) {
        return invitationRepository.findByWorkspaceIdAndStatus(workspaceId, "PENDING");
    }

    /**
     * Cancel invitation
     */
    public void cancelInvitation(String token) {
        Optional<Invitation> invitationOpt = invitationRepository.findByInvitationToken(token);
        if (invitationOpt.isPresent()) {
            Invitation invitation = invitationOpt.get();
            invitation.setStatus("CANCELLED");
            invitationRepository.save(invitation);
        }
    }
}
