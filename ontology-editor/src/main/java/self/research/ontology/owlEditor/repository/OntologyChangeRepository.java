package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.OntologyChange.ChangeType;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OntologyChangeRepository extends MongoRepository<OntologyChange, String> {
    
    /**
     * Find all changes for a specific project
     */
    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId);
    
    /**
     * Find changes by project with pagination
     */
    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId, org.springframework.data.domain.Pageable pageable);
    
    /**
     * Find changes by user
     */
    List<OntologyChange> findByUserIdOrderByTimestampDesc(String userId);
    
    /**
     * Find changes for a specific entity
     */
    List<OntologyChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI);
    
    /**
     * Find changes by type
     */
    List<OntologyChange> findByProjectIdAndChangeTypeOrderByTimestampDesc(String projectId, ChangeType changeType);
    
    /**
     * Find changes in a time range
     */
    List<OntologyChange> findByProjectIdAndTimestampBetweenOrderByTimestampDesc(
        String projectId, 
        LocalDateTime start, 
        LocalDateTime end
    );
    
    /**
     * Find changes by session
     */
    List<OntologyChange> findBySessionIdOrderByTimestampDesc(String sessionId);
    
    /**
     * Find recent changes (last N)
     */
    @Query("{ 'projectId': ?0, 'reverted': false }")
    List<OntologyChange> findRecentChanges(String projectId, org.springframework.data.domain.Pageable pageable);
    
    /**
     * Find changes by category
     */
    List<OntologyChange> findByProjectIdAndChangeCategoryOrderByTimestampDesc(String projectId, String category);
    
    /**
     * Find reverted changes
     */
    List<OntologyChange> findByProjectIdAndRevertedTrueOrderByTimestampDesc(String projectId);
    
    /**
     * Count changes by project
     */
    long countByProjectId(String projectId);
    
    /**
     * Count changes by user in project
     */
    long countByProjectIdAndUserId(String projectId, String userId);
    
    /**
     * Find changes since a specific time
     */
    List<OntologyChange> findByProjectIdAndTimestampAfterOrderByTimestampDesc(String projectId, LocalDateTime after);
    
    /**
     * Delete all changes for a project
     */
    void deleteByProjectId(String projectId);
}