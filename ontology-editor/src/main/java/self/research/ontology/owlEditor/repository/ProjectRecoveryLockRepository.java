package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;

@Repository
public interface ProjectRecoveryLockRepository extends MongoRepository<ProjectRecoveryLockDocument, String> {
}
