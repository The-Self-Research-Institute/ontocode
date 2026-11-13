package self.research.ontology.reasoner.repository;

import self.research.ontology.reasoner.model.ReasoningJobRecord;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReasoningJobRecordRepository {

    ReasoningJobRecord save(ReasoningJobRecord record);

    List<ReasoningJobRecord> saveAll(Iterable<ReasoningJobRecord> records);

    Optional<ReasoningJobRecord> findById(String jobId);

    List<ReasoningJobRecord> findByStatusIn(Collection<String> statuses);

    long deleteByUpdatedAtBefore(Instant cutoff);
}
