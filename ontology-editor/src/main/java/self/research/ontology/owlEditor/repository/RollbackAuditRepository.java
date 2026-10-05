package self.research.ontology.owlEditor.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.RollbackAudit;

import java.util.List;
import java.util.Optional;

@Repository
public interface RollbackAuditRepository extends MongoRepository<RollbackAudit, String> {

    List<RollbackAudit> findByHistoryChangeId(String historyChangeId);

    Optional<RollbackAudit> findFirstByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId, String direction);

    List<RollbackAudit> findByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId, String direction);

    Optional<RollbackAudit> findByHistoryChangeIdAndSubChangeId(String historyChangeId, String subChangeId);
}
