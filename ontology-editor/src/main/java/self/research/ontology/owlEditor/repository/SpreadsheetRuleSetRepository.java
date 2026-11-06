package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;

import java.util.List;
import java.util.Optional;

public interface SpreadsheetRuleSetRepository {

    List<SpreadsheetRuleSetEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId);

    Optional<SpreadsheetRuleSetEntity> findByProjectIdAndName(String projectId, String name);

    Optional<SpreadsheetRuleSetEntity> findById(String id);

    SpreadsheetRuleSetEntity save(SpreadsheetRuleSetEntity entity);

    void delete(SpreadsheetRuleSetEntity entity);
}
