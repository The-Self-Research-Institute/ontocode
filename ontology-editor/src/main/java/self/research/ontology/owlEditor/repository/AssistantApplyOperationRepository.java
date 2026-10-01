package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssistantApplyOperationRepository extends MongoRepository<AssistantApplyOperationDocument, String> {

    Optional<AssistantApplyOperationDocument> findFirstByGroupIdOrderByCreatedAtDesc(String groupId);

    List<AssistantApplyOperationDocument> findByStatusIn(Collection<ApplyOperationStatus> statuses);

    List<AssistantApplyOperationDocument> findByProjectIdAndStatusIn(String projectId, Collection<ApplyOperationStatus> statuses);

    long deleteByStatusInAndUpdatedAtBefore(Collection<ApplyOperationStatus> statuses, Instant cutoff);
}
