package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.DraftSession;

import java.util.List;
import java.util.Optional;

public interface DraftSessionRepository {

    DraftSession save(DraftSession session);

    Optional<DraftSession> findById(String id);

    Optional<DraftSession> findByProjectIdAndUserId(String projectId, String userId);

    List<DraftSession> findByProjectId(String projectId);

    void deleteByProjectIdAndUserId(String projectId, String userId);

    void delete(DraftSession session);

    List<DraftSession> findByUserIdStartingWith(String userIdPrefix);
}
