package self.research.ontology.swrl.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;
import java.util.Optional;

@Repository
public interface SwrlRuleRepository extends MongoRepository<SwrlRule, String> {

    List<SwrlRule> findByProjectIdAndMigratedAtIsNull(String projectId);

    Optional<SwrlRule> findByIdAndProjectId(String id, String projectId);
}
