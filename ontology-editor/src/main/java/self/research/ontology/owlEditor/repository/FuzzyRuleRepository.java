package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.FuzzyRuleEntity;

import java.util.List;
import java.util.Optional;

public interface FuzzyRuleRepository {

    List<FuzzyRuleEntity> findByProjectId(String projectId);

    Optional<FuzzyRuleEntity> findById(String id);

    FuzzyRuleEntity save(FuzzyRuleEntity entity);

    void deleteById(String id);
}
