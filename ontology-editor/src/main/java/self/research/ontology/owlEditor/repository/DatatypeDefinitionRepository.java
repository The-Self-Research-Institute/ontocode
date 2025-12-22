package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatatypeDefinitionRepository extends MongoRepository<DatatypeDefinitionEntity, String> {

    List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriOrderByCreatedAtDesc(String projectId, String datatypeIri);

    Optional<DatatypeDefinitionEntity> findByIdAndProjectId(String id, String projectId);
}
