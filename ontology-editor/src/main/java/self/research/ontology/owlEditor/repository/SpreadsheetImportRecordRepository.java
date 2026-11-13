package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;

import java.util.List;
import java.util.Optional;

public interface SpreadsheetImportRecordRepository {

    List<SpreadsheetImportRecord> findByProjectIdAndUserIdAndDraftTrue(String projectId, String userId);

    Optional<SpreadsheetImportRecord> findById(String id);

    SpreadsheetImportRecord save(SpreadsheetImportRecord record);

    void deleteById(String id);
}
