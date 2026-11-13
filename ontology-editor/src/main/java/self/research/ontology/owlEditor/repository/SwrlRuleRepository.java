package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.SwrlRuleEntity;

import java.util.List;
import java.util.Optional;

public interface SwrlRuleRepository {

    List<SwrlRuleEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    Optional<SwrlRuleEntity> findById(String id);

    SwrlRuleEntity save(SwrlRuleEntity entity);

    void deleteById(String id);
}
