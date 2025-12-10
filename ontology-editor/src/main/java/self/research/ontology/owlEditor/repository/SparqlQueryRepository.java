package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;

import java.util.List;

@Repository
public interface SparqlQueryRepository extends MongoRepository<SparqlQueryEntity, String> {
    List<SparqlQueryEntity> findByProjectId(String projectId);
}