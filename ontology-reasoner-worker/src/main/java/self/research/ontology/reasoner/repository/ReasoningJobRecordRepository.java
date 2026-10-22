package self.research.ontology.reasoner.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.reasoner.model.ReasoningJobRecord;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ReasoningJobRecordRepository extends MongoRepository<ReasoningJobRecord, String> {

    List<ReasoningJobRecord> findByStatusIn(Collection<String> statuses);

    long deleteByUpdatedAtBefore(Instant cutoff);
}
