package self.research.ontology.owlEditor.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class DatatypeDefinitionService {

    private final DatatypeDefinitionRepository repository;
    private final OntologyMutationService mutationService;

    public DatatypeDefinitionService(DatatypeDefinitionRepository repository,
                                     @Lazy OntologyMutationService mutationService) {
        this.repository = repository;
        this.mutationService = mutationService;
    }

    public List<DatatypeDefinitionEntity> listDefinitions(String projectId, String datatypeIri) {
        return repository.findByProjectIdAndDatatypeIriOrderByCreatedAtDesc(projectId, datatypeIri);
    }

    public DatatypeDefinitionEntity createDefinition(String projectId, String datatypeIri, String expression, String definitionType) {
        mutationService.apply(projectId, List.of(
                new OntologyMutationService.MutationOp(
                        "addDatatypeDefinition", datatypeIri, null, null, null, expression,
                        null, null, null, null, null, null, null, null)));

        DatatypeDefinitionEntity entity = new DatatypeDefinitionEntity();
        entity.setProjectId(projectId);
        entity.setDatatypeIri(datatypeIri);
        entity.setExpression(expression);
        entity.setDefinitionType(definitionType);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        return repository.save(entity);
    }

    public Optional<DatatypeDefinitionEntity> updateDefinition(String projectId, String id, String expression, String definitionType) {
        Optional<DatatypeDefinitionEntity> existing = repository.findByIdAndProjectId(id, projectId);
        existing.ifPresent(def -> {
            mutationService.apply(projectId, List.of(
                    new OntologyMutationService.MutationOp(
                            "deleteDatatypeDefinition", def.getDatatypeIri(), null, null, null, null,
                            null, null, null, null, null, null, null, null)));
            String newExpression = (expression != null && !expression.isBlank()) ? expression : def.getExpression();
            mutationService.apply(projectId, List.of(
                    new OntologyMutationService.MutationOp(
                            "addDatatypeDefinition", def.getDatatypeIri(), null, null, null, newExpression,
                            null, null, null, null, null, null, null, null)));

            if (expression != null && !expression.isBlank()) {
                def.setExpression(expression);
            }
            if (definitionType != null && !definitionType.isBlank()) {
                def.setDefinitionType(definitionType);
            }
            def.setUpdatedAt(new Date());
            repository.save(def);
        });
        return existing;
    }

    public Optional<DatatypeDefinitionEntity> findById(String projectId, String id) {
        return repository.findByIdAndProjectId(id, projectId);
    }

    public boolean deleteDefinition(String projectId, String id) {
        Optional<DatatypeDefinitionEntity> existing = repository.findByIdAndProjectId(id, projectId);
        if (existing.isEmpty()) {
            return false;
        }
        mutationService.apply(projectId, List.of(
                new OntologyMutationService.MutationOp(
                        "deleteDatatypeDefinition", existing.get().getDatatypeIri(), null, null, null, null,
                        null, null, null, null, null, null, null, null)));
        repository.delete(existing.get());
        return true;
    }
}
