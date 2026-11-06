package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.UndoIntentDocument;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UndoIntentRepository {

    UndoIntentDocument save(UndoIntentDocument intent);

    Optional<UndoIntentDocument> findById(String id);

    void deleteByProjectId(String projectId);

    List<UndoIntentDocument> findByProjectIdAndStatusIn(String projectId, Collection<UndoIntentDocument.Status> statuses);

    List<UndoIntentDocument> findByStatusIn(Collection<UndoIntentDocument.Status> statuses);

    Optional<UndoIntentDocument> findByProjectIdAndOpId(String projectId, String opId);

    List<UndoIntentDocument> findByStatusAndCreatedAtBefore(UndoIntentDocument.Status status, Instant before);
}
