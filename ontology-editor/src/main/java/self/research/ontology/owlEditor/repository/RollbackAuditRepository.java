package self.research.ontology.owlEditor.repository;

import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.RollbackAudit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RollbackAuditRepository {

    RollbackAudit save(RollbackAudit audit);

    Optional<RollbackAudit> findById(String id);

    void deleteById(String id);

    void deleteByProjectId(String projectId);

    List<RollbackAudit> findByHistoryChangeId(String historyChangeId);

    Optional<RollbackAudit> findFirstByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId, String direction);

    List<RollbackAudit> findByChangeSetIdAndDirectionOrderByRevertedAtDesc(String changeSetId, String direction);

    Optional<RollbackAudit> findByHistoryChangeIdAndSubChangeId(String historyChangeId, String subChangeId);

    List<RollbackAudit> findByProjectIdAndDirectionAndRevertedByUserIdInOrderByRevertedAtDesc(
            String projectId, String direction, Collection<String> userIds, Pageable pageable);
}
