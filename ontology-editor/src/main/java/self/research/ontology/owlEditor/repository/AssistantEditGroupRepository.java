package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssistantEditGroupRepository extends MongoRepository<AssistantEditGroupDocument, String> {
    Optional<AssistantEditGroupDocument> findByIdAndUserEmail(String id, String userEmail);

    List<AssistantEditGroupDocument> findByProjectIdAndTargetPathAndStatus(String projectId, String targetPath, AssistantEditGroupDocument.AssistantEditGroupStatus status);
}
