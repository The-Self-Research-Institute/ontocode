package self.research.ontology.reasoner.repository;

import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;

import java.time.Instant;
import java.util.Optional;

public interface ReasoningSnapshotRecordRepository {

    ReasoningSnapshotRecord save(ReasoningSnapshotRecord record);

    Optional<ReasoningSnapshotRecord> findById(String id);

    long deleteByUpdatedAtBefore(Instant cutoff);

    long deleteByProjectIdAndRevisionLessThan(String projectId, long revision);
}
