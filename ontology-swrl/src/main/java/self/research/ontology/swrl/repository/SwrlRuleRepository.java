package self.research.ontology.swrl.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;

@Repository
public interface SwrlRuleRepository extends MongoRepository<SwrlRule, String> {
    List<SwrlRule> findByProjectId(String projectId);
    List<SwrlRule> findByProjectIdAndEnabled(String projectId, boolean enabled);
    List<SwrlRule> findByProjectIdAndCategory(String projectId, String category);
    boolean existsByProjectIdAndRuleName(String projectId, String ruleName);
}