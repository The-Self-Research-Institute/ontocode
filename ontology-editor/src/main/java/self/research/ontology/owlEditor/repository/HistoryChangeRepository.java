package self.research.ontology.owlEditor.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface HistoryChangeRepository extends MongoRepository<HistoryChange, String> {
    
    void deleteByProjectId(String projectId);

    List<HistoryChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<HistoryChange> findByProjectIdAndChangeSetIdOrderByTimestampDesc(String projectId, String changeSetId);
    
    List<HistoryChange> findByProjectIdAndStatusOrderByTimestampDesc(String projectId, String status);
    
    Optional<HistoryChange> findByProjectIdAndEditId(String projectId, String editId);
    
    Optional<HistoryChange> findByEditId(String editId);
    
    boolean existsByProjectIdAndEditId(String projectId, String editId);
    
    List<HistoryChange> findByProjectIdAndHasConflictOrderByTimestampDesc(String projectId, boolean hasConflict);
    
    List<HistoryChange> findByProjectIdAndUserIdOrderByTimestampDesc(String projectId, String userId);

    List<HistoryChange> findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(String projectId, boolean draft,
                                                                                 Collection<String> userIds, Pageable pageable);

    List<HistoryChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI, Pageable pageable);

    List<HistoryChange> findByProjectId(String projectId, Pageable pageable);
}
