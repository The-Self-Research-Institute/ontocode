package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.owlEditor.document.EntityUsageDocument;

import java.util.List;
import java.util.Optional;

public interface EntityUsageRepository extends MongoRepository<EntityUsageDocument, String> {

    Optional<EntityUsageDocument> findByProjectIdAndEntityIri(String projectId, String entityIri);

    void deleteByProjectIdAndEntityIriIn(String projectId, List<String> entityIris);

    void deleteByProjectId(String projectId);
}
