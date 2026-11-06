package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;

import java.time.Instant;
import java.util.Optional;

public interface ProjectRecoveryLockRepository {

    Optional<ProjectRecoveryLockDocument> findById(String projectId);

    ProjectRecoveryLockDocument save(ProjectRecoveryLockDocument lock);

    boolean releaseIfLocked(String projectId, String actor, String action, Instant now);
}
