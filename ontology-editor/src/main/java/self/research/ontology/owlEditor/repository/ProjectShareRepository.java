package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.ProjectShare;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectShareRepository extends MongoRepository<ProjectShare, String> {
    
    Optional<ProjectShare> findByProjectId(String projectId);
    
    List<ProjectShare> findByOwnerEmail(String ownerEmail);
    
    List<ProjectShare> findBySharedWithEmailsContaining(String email);
    
    Optional<ProjectShare> findByShareLink(String shareLink);
    
    boolean existsByProjectId(String projectId);
}
