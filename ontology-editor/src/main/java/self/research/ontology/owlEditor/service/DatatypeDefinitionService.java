package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class DatatypeDefinitionService {

    private final DatatypeDefinitionRepository repository;

    public DatatypeDefinitionService(DatatypeDefinitionRepository repository) {
        this.repository = repository;
    }

    public List<DatatypeDefinitionEntity> listDefinitions(String projectId, String datatypeIri) {
        return repository.findByProjectIdAndDatatypeIriOrderByCreatedAtDesc(projectId, datatypeIri);
    }

    public DatatypeDefinitionEntity createDefinition(String projectId, String datatypeIri, String expression, String definitionType) {
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

    public boolean deleteDefinition(String projectId, String id) {
        Optional<DatatypeDefinitionEntity> existing = repository.findByIdAndProjectId(id, projectId);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        return true;
    }
}
