package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.ConsistencyCheckState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AssistantEditGroupRepository {

    AssistantEditGroupDocument save(AssistantEditGroupDocument group);

    List<AssistantEditGroupDocument> saveAll(Iterable<AssistantEditGroupDocument> groups);

    Optional<AssistantEditGroupDocument> findById(String id);

    List<AssistantEditGroupDocument> findAllById(Iterable<String> ids);

    Optional<AssistantEditGroupDocument> findByIdAndUserEmail(String id, String userEmail);

    List<AssistantEditGroupDocument> findByProjectIdAndTargetPathAndStatus(String projectId, String targetPath,
                                                                          AssistantEditGroupStatus status);

    boolean resolveConsistencyCheck(String id, String jobId, ConsistencyCheckState state, String detail,
                                    Instant resolvedAt);
}
