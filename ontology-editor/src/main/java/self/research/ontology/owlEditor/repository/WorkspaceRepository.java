package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.WorkspaceDocument;

import java.util.Optional;

public interface WorkspaceRepository {

    Optional<WorkspaceDocument> findByWorkspaceId(String workspaceId);
}
