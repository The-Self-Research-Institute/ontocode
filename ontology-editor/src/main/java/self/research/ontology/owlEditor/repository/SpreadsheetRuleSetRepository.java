package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpreadsheetRuleSetRepository extends MongoRepository<SpreadsheetRuleSetEntity, String> {
    List<SpreadsheetRuleSetEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    Optional<SpreadsheetRuleSetEntity> findByProjectIdAndName(String projectId, String name);
}
