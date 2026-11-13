package self.research.ontology.owlEditor.repository;

import org.springframework.data.domain.Pageable;
import self.research.ontology.owlEditor.model.HistoryChange;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HistoryChangeRepository {

    record DraftGroup(String projectId, String userId, long rows) {
    }

    record SameNameGroup(String projectId, String username, List<String> userIds) {
    }

    HistoryChange save(HistoryChange change);

    List<HistoryChange> saveAll(List<HistoryChange> changes);

    Optional<HistoryChange> findById(String id);

    void deleteByProjectId(String projectId);

    List<HistoryChange> findByProjectIdOrderByTimestampDesc(String projectId);

    List<HistoryChange> findByProjectIdAndChangeSetIdOrderByTimestampDesc(String projectId, String changeSetId);

    List<HistoryChange> findByProjectIdAndStatusOrderByTimestampDesc(String projectId, String status);

    Optional<HistoryChange> findByEditId(String editId);

    List<HistoryChange> findByProjectIdAndImportId(String projectId, String importId);

    long markDraftEntriesShared(String projectId, String entityIri);

    boolean existsByProjectIdAndEditId(String projectId, String editId);

    List<HistoryChange> findByProjectIdAndDraftAndUserIdInOrderByTimestampDesc(String projectId, boolean draft,
                                                                                 Collection<String> userIds, Pageable pageable);

    List<HistoryChange> findByProjectIdAndEntityIRIOrderByTimestampDesc(String projectId, String entityIRI, Pageable pageable);

    List<HistoryChange> findByProjectId(String projectId, Pageable pageable);

    List<HistoryChange> findByProjectIdAndPublishedPrId(String projectId, String publishedPrId);

    List<HistoryChange> findByProjectIdAndDraftOrderByTimestampDesc(String projectId, boolean draft, Pageable pageable);

    List<HistoryChange> findDraftRowsOfUser(String projectId, String userId);

    long markDraftsPublished(String projectId, String userId);

    long countUnpublishedRowsOfChangeSets(String projectId, Collection<String> changeSetIds);

    long tagRowsOfChangeSets(String projectId, Collection<String> changeSetIds, String prId);

    long countUnlinkedRows(String projectId, String userId, LocalDateTime from, LocalDateTime to,
                           Collection<String> excludedOperationTypes);

    long tagUnlinkedRows(String projectId, String userId, LocalDateTime from, LocalDateTime to,
                         Collection<String> excludedOperationTypes, String prId);

    Optional<HistoryChange> findRecord(String projectId, String entityIri, String operationType);

    boolean existsRecord(String projectId, String entityIri, String operationType);

    long markPrRowsReverted(String projectId, String prId);

    long markPrRowsRestored(String projectId, String prId);

    long setRevertedOfRecord(String projectId, String entityIri, String operationType, boolean from, boolean to);

    long tagRollbackRowsOfChangeSets(String projectId, Collection<String> changeSetIds, String prId);

    List<HistoryChange> findRollbackRowsOfPr(String projectId, String prId);

    long markChangeSetsPublished(String projectId, Collection<String> changeSetIds);

    List<String> findLiveDraftChangeSetIds(String projectId, String userId);

    long markDraftsDiscarded(String projectId, String userId, Collection<String> keepChangeSetIds);

    void setEntryReverted(String id, boolean reverted, String auditId);

    void setSubChangesReverted(String id, Collection<String> subChangeIds, boolean reverted, String auditId);

    int backfillSubChangeIds();

    List<DraftGroup> countLiveDraftGroups();

    List<SameNameGroup> findNamesUnderSeveralUserIds();

    long tagOperationGroup(String projectId, String userId, LocalDateTime since, String groupId);

    List<HistoryChange> findOpenByOperationGroupId(String operationGroupId);

    void blockExact(String id, String editId, String projectId, String reason);

    void setExactCovers(String projectId, String editId, int covers);
}
