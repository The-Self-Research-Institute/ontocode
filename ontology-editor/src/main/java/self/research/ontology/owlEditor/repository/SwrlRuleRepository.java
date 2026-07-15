package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;

import java.util.List;

@Repository
public interface SwrlRuleRepository extends MongoRepository<SwrlRuleEntity, String> {
    List<SwrlRuleEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}