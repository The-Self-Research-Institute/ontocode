package self.research.ontology.owlEditor.repository;

import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.OntologyChange.ChangeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OntologyChangeRepository {

    OntologyChange save(OntologyChange change);

    Optional<OntologyChange> findById(String id);

    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<OntologyChange> findByProjectIdOrderByTimestampDesc(String projectId, Pageable pageable);

    List<OntologyChange> findByUserIdOrderByTimestampDesc(String userId);

    List<OntologyChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI);

    List<OntologyChange> findByProjectIdAndChangeTypeOrderByTimestampDesc(String projectId, ChangeType changeType);

    List<OntologyChange> findByProjectIdAndTimestampBetweenOrderByTimestampDesc(
        String projectId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<OntologyChange> findBySessionIdOrderByTimestampDesc(String sessionId);

    List<OntologyChange> findRecentChanges(String projectId, Pageable pageable);

    List<OntologyChange> findByProjectIdAndChangeCategoryOrderByTimestampDesc(String projectId, String category);

    List<OntologyChange> findByProjectIdAndRevertedTrueOrderByTimestampDesc(String projectId);

    long countByProjectId(String projectId);

    long countByProjectIdAndUserId(String projectId, String userId);

    List<OntologyChange> findByProjectIdAndTimestampAfterOrderByTimestampDesc(String projectId, LocalDateTime after);

    void deleteByProjectId(String projectId);
}
