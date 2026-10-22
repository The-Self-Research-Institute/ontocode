package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;

import java.util.List;

@Repository
public interface SpreadsheetImportRecordRepository extends MongoRepository<SpreadsheetImportRecord, String> {

    List<SpreadsheetImportRecord> findByProjectIdAndUserIdAndDraftTrue(String projectId, String userId);
}
