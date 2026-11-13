package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssistantApplyOperationRepository {

    AssistantApplyOperationDocument save(AssistantApplyOperationDocument operation);

    Optional<AssistantApplyOperationDocument> findById(String id);

    Optional<AssistantApplyOperationDocument> findFirstByGroupIdOrderByCreatedAtDesc(String groupId);

    List<AssistantApplyOperationDocument> findByStatusIn(Collection<ApplyOperationStatus> statuses);

    List<AssistantApplyOperationDocument> findByProjectIdAndStatusIn(String projectId,
                                                                    Collection<ApplyOperationStatus> statuses);

    long deleteByStatusInAndUpdatedAtBefore(Collection<ApplyOperationStatus> statuses, Instant cutoff);
}
