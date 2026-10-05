package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;

import java.util.Optional;

@Repository
public interface AssistantSessionRepository extends MongoRepository<AssistantSessionDocument, String> {
    Optional<AssistantSessionDocument> findByIdAndUserEmail(String id, String userEmail);
}
