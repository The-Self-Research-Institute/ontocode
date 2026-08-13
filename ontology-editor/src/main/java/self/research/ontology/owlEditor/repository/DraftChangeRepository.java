package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DraftChange;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DraftChangeRepository extends MongoRepository<DraftChange, String> {

    List<DraftChange> findByProjectIdAndAppliedFalseOrderByTimestampAsc(String projectId);

    List<DraftChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<DraftChange> findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(String projectId, String userId);

    List<DraftChange> findByProjectIdAndTimestampBetweenOrderByTimestampAsc(
        String projectId, LocalDateTime start, LocalDateTime end);

    void deleteByProjectId(String projectId);

    void deleteByProjectIdAndAppliedTrue(String projectId);

    long countByProjectIdAndAppliedFalse(String projectId);
}
