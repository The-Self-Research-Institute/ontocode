package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.Invitation;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends MongoRepository<Invitation, String> {
    
    Optional<Invitation> findByInvitationToken(String invitationToken);
    
    List<Invitation> findByInviteeEmail(String inviteeEmail);
    
    List<Invitation> findByWorkspaceId(String workspaceId);
    
    List<Invitation> findByWorkspaceIdAndStatus(String workspaceId, String status);
    
    Optional<Invitation> findByInviteeEmailAndWorkspaceIdAndStatus(String inviteeEmail, String workspaceId, String status);
    
    Optional<Invitation> findByInviteeEmailAndWorkspaceId(String inviteeEmail, String workspaceId);
    
    boolean existsByInvitationToken(String invitationToken);
}
