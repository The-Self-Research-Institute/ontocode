package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.UndoIntentDocument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UndoIntentRepository extends MongoRepository<UndoIntentDocument, String> {

    void deleteByProjectId(String projectId);

    Optional<UndoIntentDocument> findByProjectIdAndOpId(String projectId, String opId);

    List<UndoIntentDocument> findByStatusAndCreatedAtBefore(UndoIntentDocument.Status status, Instant before);
}
