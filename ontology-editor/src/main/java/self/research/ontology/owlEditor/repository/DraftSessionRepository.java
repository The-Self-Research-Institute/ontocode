package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DraftSession;

import java.util.Optional;

@Repository
public interface DraftSessionRepository extends MongoRepository<DraftSession, String> {

    Optional<DraftSession> findByProjectIdAndUserId(String projectId, String userId);

    void deleteByProjectIdAndUserId(String projectId, String userId);
}
