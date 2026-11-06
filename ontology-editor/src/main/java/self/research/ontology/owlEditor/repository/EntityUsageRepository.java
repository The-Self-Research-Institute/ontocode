package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.EntityUsageDocument;

import java.util.List;
import java.util.Optional;

public interface EntityUsageRepository {

    Optional<EntityUsageDocument> findByProjectIdAndEntityIri(String projectId, String entityIri);

    void saveAll(List<EntityUsageDocument> documents);

    void deleteByProjectIdAndEntityIriIn(String projectId, List<String> entityIris);

    void deleteByProjectId(String projectId);
}
