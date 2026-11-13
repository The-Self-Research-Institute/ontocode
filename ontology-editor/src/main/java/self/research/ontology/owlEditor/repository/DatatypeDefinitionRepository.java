package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;

import java.util.List;
import java.util.Optional;

public interface DatatypeDefinitionRepository {

    List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc(
            String projectId, String datatypeIri, boolean draft);

    List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue(
            String projectId, String datatypeIri, String ownerUserId);

    List<DatatypeDefinitionEntity> findByProjectIdAndOwnerUserIdAndDraftTrue(String projectId, String ownerUserId);

    Optional<DatatypeDefinitionEntity> findByIdAndProjectId(String id, String projectId);

    Optional<DatatypeDefinitionEntity> findById(String id);

    DatatypeDefinitionEntity save(DatatypeDefinitionEntity entity);

    void delete(DatatypeDefinitionEntity entity);

    void deleteById(String id);

    void deleteAll(Iterable<? extends DatatypeDefinitionEntity> entities);
}
