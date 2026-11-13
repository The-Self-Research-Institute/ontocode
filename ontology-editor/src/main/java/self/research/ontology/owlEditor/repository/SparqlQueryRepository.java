package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.SparqlQueryEntity;

import java.util.List;
import java.util.Optional;

public interface SparqlQueryRepository {

    List<SparqlQueryEntity> findByProjectId(String projectId);

    Optional<SparqlQueryEntity> findById(String id);

    SparqlQueryEntity save(SparqlQueryEntity entity);

    void deleteById(String id);
}
