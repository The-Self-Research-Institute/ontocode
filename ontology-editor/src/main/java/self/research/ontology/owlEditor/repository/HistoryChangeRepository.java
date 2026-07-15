package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.List;
import java.util.Optional;

@Repository
public interface HistoryChangeRepository extends MongoRepository<HistoryChange, String> {
    
    List<HistoryChange> findByProjectIdOrderByTimestampDesc(String projectId);
    
    List<HistoryChange> findByProjectIdAndStatusOrderByTimestampDesc(String projectId, String status);
    
    Optional<HistoryChange> findByProjectIdAndEditId(String projectId, String editId);
    
    Optional<HistoryChange> findByEditId(String editId);
    
    boolean existsByProjectIdAndEditId(String projectId, String editId);
    
    List<HistoryChange> findByProjectIdAndHasConflictOrderByTimestampDesc(String projectId, boolean hasConflict);
    
    List<HistoryChange> findByProjectIdAndUserIdOrderByTimestampDesc(String projectId, String userId);
}
