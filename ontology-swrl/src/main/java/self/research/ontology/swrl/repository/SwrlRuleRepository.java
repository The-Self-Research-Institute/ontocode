package self.research.ontology.swrl.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;
import java.util.Optional;

@Repository
public interface SwrlRuleRepository extends MongoRepository<SwrlRule, String> {

    List<SwrlRule> findByProjectId(String projectId);

    List<SwrlRule> findByProjectIdAndEnabled(String projectId, boolean enabled);

    Optional<SwrlRule> findByIdAndProjectId(String id, String projectId);

    boolean existsByProjectIdAndRuleName(String projectId, String ruleName);

    // Paginated queries
    Page<SwrlRule> findByProjectId(String projectId, Pageable pageable);

    Page<SwrlRule> findByProjectIdAndEnabled(String projectId, Boolean enabled, Pageable pageable);

    Page<SwrlRule> findByProjectIdAndCategory(String projectId, String category, Pageable pageable);

    // ✅ ADD THIS METHOD (for search)
    Page<SwrlRule> findByProjectIdAndRuleNameContainingOrRuleTextContaining(
            String projectId, String ruleNameSearch, String ruleTextSearch, Pageable pageable);
}