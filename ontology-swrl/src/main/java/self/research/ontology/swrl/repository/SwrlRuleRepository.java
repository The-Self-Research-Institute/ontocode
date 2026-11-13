package self.research.ontology.swrl.repository;

import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;
import java.util.Optional;

public interface SwrlRuleRepository {

    List<SwrlRule> findByProjectIdAndMigratedAtIsNull(String projectId);

    Optional<SwrlRule> findByIdAndProjectId(String id, String projectId);

    SwrlRule save(SwrlRule rule);
}
