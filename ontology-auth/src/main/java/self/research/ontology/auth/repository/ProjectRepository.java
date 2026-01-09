package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.Project;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends MongoRepository<Project, String> {
    
    Optional<Project> findByProjectId(String projectId);
    
    List<Project> findByWorkspaceId(String workspaceId);
    
    List<Project> findByWorkspaceIdAndStatus(String workspaceId, String status);
    
    List<Project> findByOwnerId(String ownerId);
    
    List<Project> findByOwnerIdAndStatus(String ownerId, String status);
    
    // Find projects where user is a member
    List<Project> findByMembers_UserId(String userId);
    
    boolean existsByProjectId(String projectId);
}
