package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.Invitation;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository {

    Invitation save(Invitation invitation);

    Optional<Invitation> findByInvitationToken(String invitationToken);

    List<Invitation> findByWorkspaceIdAndStatus(String workspaceId, String status);

    Optional<Invitation> findByInviteeEmailAndWorkspaceId(String inviteeEmail, String workspaceId);

    Optional<Invitation> findByInviteeEmailAndWorkspaceIdAndStatus(String inviteeEmail, String workspaceId,
                                                                   String status);

    boolean existsByInvitationToken(String invitationToken);

    void deleteByWorkspaceId(String workspaceId);
}
