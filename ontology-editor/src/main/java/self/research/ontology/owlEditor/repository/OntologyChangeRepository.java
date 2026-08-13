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

    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId, org.springframework.data.domain.Pageable pageable);

    List<OntologyChange> findByUserIdOrderByTimestampDesc(String userId);

    List<OntologyChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI);

    List<OntologyChange> findByProjectIdAndChangeTypeOrderByTimestampDesc(String projectId, ChangeType changeType);

    List<OntologyChange> findByProjectIdAndTimestampBetweenOrderByTimestampDesc(
        String projectId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<OntologyChange> findBySessionIdOrderByTimestampDesc(String sessionId);

    @Query("{ 'projectId': ?0, 'reverted': false }")
    List<OntologyChange> findRecentChanges(String projectId, org.springframework.data.domain.Pageable pageable);

    List<OntologyChange> findByProjectIdAndChangeCategoryOrderByTimestampDesc(String projectId, String category);

    List<OntologyChange> findByProjectIdAndRevertedTrueOrderByTimestampDesc(String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndUserId(String projectId, String userId);

    List<OntologyChange> findByProjectIdAndTimestampAfterOrderByTimestampDesc(String projectId, LocalDateTime after);

    void deleteByProjectId(String projectId);
}