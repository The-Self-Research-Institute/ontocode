package self.research.ontology.reasoner.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;

import java.time.Instant;

public interface ReasoningSnapshotRecordRepository extends MongoRepository<ReasoningSnapshotRecord, String> {

    long deleteByUpdatedAtBefore(Instant cutoff);

    long deleteByProjectIdAndRevisionLessThan(String projectId, long revision);
}
