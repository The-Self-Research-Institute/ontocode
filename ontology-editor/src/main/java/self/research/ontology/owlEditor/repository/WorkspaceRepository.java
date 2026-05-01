package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.WorkspaceDocument;

import java.util.Optional;

@Repository
public interface WorkspaceRepository extends MongoRepository<WorkspaceDocument, String> {
    Optional<WorkspaceDocument> findByWorkspaceId(String workspaceId);
}
