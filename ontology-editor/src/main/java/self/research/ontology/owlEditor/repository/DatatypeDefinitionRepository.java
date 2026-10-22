package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatatypeDefinitionRepository extends MongoRepository<DatatypeDefinitionEntity, String> {

    List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc(
            String projectId, String datatypeIri, boolean draft);

    List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue(
            String projectId, String datatypeIri, String ownerUserId);

    List<DatatypeDefinitionEntity> findByProjectIdAndOwnerUserIdAndDraftTrue(String projectId, String ownerUserId);

    Optional<DatatypeDefinitionEntity> findByIdAndProjectId(String id, String projectId);
}
