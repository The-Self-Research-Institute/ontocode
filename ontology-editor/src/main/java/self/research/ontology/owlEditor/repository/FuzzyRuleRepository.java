package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;

import java.util.List;

@Repository
public interface FuzzyRuleRepository extends MongoRepository<FuzzyRuleEntity, String> {
    List<FuzzyRuleEntity> findByProjectId(String projectId);
}
