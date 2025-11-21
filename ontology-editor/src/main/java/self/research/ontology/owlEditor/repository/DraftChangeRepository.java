package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DraftChange;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DraftChangeRepository extends MongoRepository<DraftChange, String> {
    
    /**
     * Find all unapplied drafts for a project
     */
    List<DraftChange> findByProjectIdAndAppliedFalseOrderByTimestampAsc(String projectId);
    
    /**
     * Find all drafts for a project (applied and unapplied)
     */
    List<DraftChange> findByProjectIdOrderByTimestampDesc(String projectId);
    
    /**
     * Find drafts by user
     */
    List<DraftChange> findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(String projectId, String userId);
    
    /**
     * Find drafts in time range
     */
    List<DraftChange> findByProjectIdAndTimestampBetweenOrderByTimestampAsc(
        String projectId, LocalDateTime start, LocalDateTime end);
    
    /**
     * Delete all drafts for a project
     */
    void deleteByProjectId(String projectId);
    
    /**
     * Delete all applied drafts for a project
     */
    void deleteByProjectIdAndAppliedTrue(String projectId);
    
    /**
     * Count unapplied drafts for a project
     */
    long countByProjectIdAndAppliedFalse(String projectId);
}
