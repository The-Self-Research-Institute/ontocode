package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.DraftChange;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DraftChangeRepository {

    DraftChange save(DraftChange change);

    List<DraftChange> saveAll(List<DraftChange> changes);

    Optional<DraftChange> findById(String id);

    void deleteById(String id);

    List<DraftChange> findByProjectIdAndAppliedFalseOrderByTimestampAsc(String projectId);

    List<DraftChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<DraftChange> findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(String projectId, String userId);

    List<DraftChange> findByProjectIdAndTimestampBetweenOrderByTimestampAsc(
        String projectId, LocalDateTime start, LocalDateTime end);

    void deleteByProjectId(String projectId);

    void deleteByProjectIdAndAppliedTrue(String projectId);

    long countByProjectIdAndAppliedFalse(String projectId);

    long deleteByAppliedFalseAndTimestampBefore(LocalDateTime cutoff);

    int parkPending(String projectId, String userId, String sessionId);

    int restoreParked(String projectId, String userId, String sessionId);

    int deletePending(String projectId, String userId, String sessionId);
}
