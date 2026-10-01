package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;

public interface AssistantAuditRepository extends MongoRepository<AssistantAuditEntryDocument, String> {
}
