package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.InvitationRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class InvitationCleanupService {

    private final InvitationRepository invitationRepository;
    private final WorkspaceRepository workspaceRepository;

    public InvitationCleanupService(InvitationRepository invitationRepository,
                                    WorkspaceRepository workspaceRepository) {
        this.invitationRepository = invitationRepository;
        this.workspaceRepository = workspaceRepository;
    }

    // Runs daily at 02:00 server time
    @Scheduled(cron = "0 0 2 * * *")
    public void expireStaleInvitations() {
        LocalDateTime now = LocalDateTime.now();
        List<Invitation> expired = invitationRepository.findByStatusAndExpiresAtBefore("PENDING", now);

        if (expired.isEmpty()) {
            return;
        }

        log.info("[InvitationCleanup] Expiring {} stale invitations", expired.size());

        for (Invitation inv : expired) {
            inv.setStatus("EXPIRED");
            invitationRepository.save(inv);

            // Remove the corresponding PENDING workspace member
            workspaceRepository.findByWorkspaceId(inv.getWorkspaceId()).ifPresent(ws -> {
                boolean removed = ws.getMembers().removeIf(m ->
                        m.getStatus() == Workspace.MemberStatus.PENDING
                        && inv.getInvitationToken() != null
                        && inv.getInvitationToken().equals(m.getInvitationToken()));
                if (removed) {
                    workspaceRepository.save(ws);
                    log.debug("[InvitationCleanup] Removed pending member slot for {} in workspace {}",
                            inv.getInviteeEmail(), inv.getWorkspaceId());
                }
            });
        }

        log.info("[InvitationCleanup] Done — {} invitations marked EXPIRED", expired.size());
    }
}
