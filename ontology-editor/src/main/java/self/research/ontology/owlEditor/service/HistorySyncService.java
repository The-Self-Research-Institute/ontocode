package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistorySyncService {

    private final HistoryChangeRepository historyChangeRepository;
    private final MongoTemplate mongoTemplate;
    private final OntologyHistoryService historyService;

    @Value("${ontology.history.conflict-window-hours:24}")
    private long conflictWindowHours;

    public record ConflictMatch(String partnerChangeId, String partnerUserId, String partnerUsername,
                                String partnerValue, LocalDateTime partnerTimestamp) {
    }

    public java.util.Set<String> draftEntityIris(String projectId, String userId) {
        java.util.Set<String> iris = new java.util.LinkedHashSet<>();
        if (projectId == null || userId == null) {
            return iris;
        }
        List<HistoryChange> drafts = mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)
                                .and("userId").is(userId).and("draft").is(true)),
                HistoryChange.class);
        for (HistoryChange draft : drafts) {
            if (draft.getEntityIRI() != null) {
                iris.add(draft.getEntityIRI());
            }
        }
        return iris;
    }

    public long markDraftsPublished(String projectId, String userId) {
        if (projectId == null || userId == null) {
            return 0;
        }
        var result = mongoTemplate.updateMulti(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)
                                .and("userId").is(userId).and("draft").is(true).and("draftDiscarded").ne(true)),
                new org.springframework.data.mongodb.core.query.Update().set("draft", false),
                self.research.ontology.owlEditor.model.HistoryChange.class);
        if (result.getModifiedCount() > 0) {
            log.info("Marked {} draft history entries as published for project {} user {}",
                    result.getModifiedCount(), projectId, userId);
        }
        return result.getModifiedCount();
    }

    public long countRowsToTag(String projectId, java.util.List<String> changeSetIds) {
        if (projectId == null || changeSetIds == null || changeSetIds.isEmpty()) {
            return 0;
        }
        return mongoTemplate.count(Query.query(Criteria.where("projectId").is(projectId).and("draft").is(false)
                .and("changeSetId").in(changeSetIds).and("publishedPrId").is(null)), HistoryChange.class);
    }

    public long tagRowsOfPr(String projectId, java.util.List<String> changeSetIds, String prId) {
        if (projectId == null || prId == null || changeSetIds == null || changeSetIds.isEmpty()) {
            return 0;
        }
        var result = mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId).and("draft").is(false)
                        .and("changeSetId").in(changeSetIds).and("publishedPrId").is(null)),
                new Update().set("publishedPrId", prId), HistoryChange.class);
        return result.getModifiedCount();
    }

    private static Criteria unlinkedPublishRows(String projectId, String userId,
                                                java.time.LocalDateTime from, java.time.LocalDateTime to) {
        return Criteria.where("projectId").is(projectId).and("userId").is(userId).and("draft").is(false)
                .and("publishedPrId").is(null).and("source").ne("ROLLBACK")
                .and("operationType").nin(RollbackWorkSupport.PR_PUBLISHED, RollbackWorkSupport.PR_REVERTED)
                .and("timestamp").gte(from).lte(to)
                .orOperator(Criteria.where("changeSetId").is(null), Criteria.where("operationType").is("publishDraft"));
    }

    public long tagUnlinkedRows(String projectId, String userId, java.time.LocalDateTime from,
                                java.time.LocalDateTime to, String prId) {
        if (projectId == null || userId == null || prId == null || from == null || to == null) {
            return 0;
        }
        return mongoTemplate.updateMulti(Query.query(unlinkedPublishRows(projectId, userId, from, to)),
                new Update().set("publishedPrId", prId), HistoryChange.class).getModifiedCount();
    }

    public long countUnlinkedRows(String projectId, String userId, java.time.LocalDateTime from, java.time.LocalDateTime to) {
        if (projectId == null || userId == null || from == null || to == null) {
            return 0;
        }
        return mongoTemplate.count(Query.query(unlinkedPublishRows(projectId, userId, from, to)), HistoryChange.class);
    }

    public java.time.LocalDateTime prPublicationTime(String projectId, String prId) {
        HistoryChange record = mongoTemplate.findOne(Query.query(Criteria.where("projectId").is(projectId)
                .and("entityIRI").is(PrEntries.iriOf(prId)).and("operationType").is(RollbackWorkSupport.PR_PUBLISHED)), HistoryChange.class);
        return record == null ? null : record.getTimestamp();
    }

    public long markPrRowsReverted(String projectId, String prId) {
        if (projectId == null || prId == null) {
            return 0;
        }
        return mongoTemplate.updateMulti(Query.query(Criteria.where("projectId").is(projectId).and("publishedPrId").is(prId)
                        .and("source").ne(ChangeOrigin.ROLLBACK).and("reverted").ne(true)),
                new Update().set("reverted", true), HistoryChange.class).getModifiedCount();
    }

    public long tagRollbackRowsOfPr(String projectId, java.util.List<String> changeSetIds, String prId) {
        if (projectId == null || prId == null || changeSetIds == null || changeSetIds.isEmpty()) {
            return 0;
        }
        return mongoTemplate.updateMulti(Query.query(Criteria.where("projectId").is(projectId)
                        .and("source").is(ChangeOrigin.ROLLBACK).and("metadata.revertsChangeSetId").in(changeSetIds)
                        .and("publishedPrId").is(null)),
                new Update().set("publishedPrId", prId), HistoryChange.class).getModifiedCount();
    }

    public java.util.Set<String> setsUndoneForPr(String projectId, String prId) {
        java.util.Set<String> undone = new java.util.LinkedHashSet<>();
        if (projectId == null || prId == null) {
            return undone;
        }
        mongoTemplate.find(Query.query(Criteria.where("projectId").is(projectId).and("publishedPrId").is(prId)
                .and("source").is(ChangeOrigin.ROLLBACK).and("operationType").regex("^ROLLBACK_")), HistoryChange.class)
                .forEach(row -> {
                    if (row.getMetadata() != null && row.getMetadata().get("revertsChangeSetId") instanceof String id) {
                        undone.add(id);
                    }
                });
        return undone;
    }

    public long markPrPublicationRestored(String projectId, String prId) {
        return setReverted(Criteria.where("projectId").is(projectId).and("entityIRI").is(PrEntries.iriOf(prId))
                .and("operationType").is(RollbackWorkSupport.PR_PUBLISHED).and("reverted").is(true), false);
    }

    public long markPrRowsRestored(String projectId, String prId) {
        return setReverted(Criteria.where("projectId").is(projectId).and("publishedPrId").is(prId)
                .and("source").ne(ChangeOrigin.ROLLBACK).and("reverted").is(true), false);
    }

    public long markPrReversalUndone(String projectId, String prId) {
        return setReverted(Criteria.where("projectId").is(projectId).and("entityIRI").is(PrEntries.iriOf(prId))
                .and("operationType").is(RollbackWorkSupport.PR_REVERTED).and("reverted").ne(true), true);
    }

    private long setReverted(Criteria match, boolean reverted) {
        return mongoTemplate.updateMulti(Query.query(match), new Update().set("reverted", reverted), HistoryChange.class)
                .getModifiedCount();
    }

    public boolean hasPrPublication(String projectId, String prId) {
        return mongoTemplate.exists(Query.query(Criteria.where("projectId").is(projectId)
                .and("entityIRI").is(PrEntries.iriOf(prId)).and("operationType").is(RollbackWorkSupport.PR_PUBLISHED)), HistoryChange.class);
    }

    public java.util.List<HistoryChange> publishedRows(String projectId, String prId) {
        return historyChangeRepository.findByProjectIdAndPublishedPrId(projectId, prId).stream()
                .filter(row -> !ChangeOrigin.ROLLBACK.equals(row.getSource())).toList();
    }

    public long markChangeSetsPublished(String projectId, java.util.List<String> changeSetIds) {
        if (projectId == null || changeSetIds == null || changeSetIds.isEmpty()) {
            return 0;
        }
        var result = mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId).and("draft").is(true)
                        .and("draftDiscarded").ne(true).and("changeSetId").in(changeSetIds)),
                new Update().set("draft", false), HistoryChange.class);
        return result.getModifiedCount();
    }

    public long markPrPublicationReverted(String projectId, String prId) {
        if (projectId == null || prId == null) {
            return 0;
        }
        var result = mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId).and("entityIRI").is(PrEntries.iriOf(prId))
                        .and("operationType").is("prPublished").and("reverted").ne(true)),
                new Update().set("reverted", true), HistoryChange.class);
        return result.getModifiedCount();
    }

    public java.util.List<String> draftChangeSetIds(String projectId, String userId) {
        if (projectId == null || userId == null) {
            return java.util.List.of();
        }
        Criteria match = Criteria.where("projectId").is(projectId).and("userId").is(userId).and("draft").is(true)
                .and("draftDiscarded").ne(true).and("reverted").ne(true).and("changeSetId").ne(null);
        return mongoTemplate.findDistinct(Query.query(match), "changeSetId", HistoryChange.class, String.class);
    }

    public long markDraftsDiscarded(String projectId, String userId) {
        return markDraftsDiscarded(projectId, userId, java.util.List.of());
    }

    public long markDraftsDiscarded(String projectId, String userId, java.util.Collection<String> keepChangeSetIds) {
        if (projectId == null) {
            return 0;
        }
        Criteria match = Criteria.where("projectId").is(projectId).and("draft").is(true).and("draftDiscarded").ne(true);
        if (userId != null && !userId.isBlank()) {
            match = match.and("userId").is(userId);
        }
        if (keepChangeSetIds != null && !keepChangeSetIds.isEmpty()) {
            match = match.and("changeSetId").nin(keepChangeSetIds);
        }
        var result = mongoTemplate.updateMulti(Query.query(match), new Update().set("draftDiscarded", true), HistoryChange.class);
        if (result.getModifiedCount() > 0) {
            log.info("Marked {} draft history entries as discarded for project {} user {}",
                    result.getModifiedCount(), projectId, userId);
        }
        return result.getModifiedCount();
    }

    public void syncChange(String projectId, String editId, Map<String, Object> changeData) {

        if (historyChangeRepository.existsByProjectIdAndEditId(projectId, editId)) {
            log.debug("Change {} already synced, skipping", editId);
            return;
        }

        try {
            String userId = (String) changeData.get("userId");
            String username = (String) changeData.get("username");

            HistoryChange.Builder builder = new HistoryChange.Builder(projectId, editId, userId, username);

            if (changeData.containsKey("operationType")) {
                builder.operationType((String) changeData.get("operationType"));
            }

            if (changeData.containsKey("entityType")) {
                builder.entityType((String) changeData.get("entityType"));
            }

            if (changeData.containsKey("entityIRI")) {
                builder.entityIRI((String) changeData.get("entityIRI"));
            }

            if (changeData.containsKey("entityLabel")) {
                builder.entityLabel((String) changeData.get("entityLabel"));
            }

            if (changeData.containsKey("oldValue")) {
                builder.oldValue((String) changeData.get("oldValue"));
            }

            if (changeData.containsKey("newValue")) {
                builder.newValue((String) changeData.get("newValue"));
            }

            if (changeData.containsKey("annotationProperty")) {
                builder.annotationProperty((String) changeData.get("annotationProperty"));
            }

            if (changeData.containsKey("description")) {
                builder.description((String) changeData.get("description"));
            }

            if (changeData.containsKey("draft")) {
                builder.draft(Boolean.TRUE.equals(changeData.get("draft")));
            }
            applyChangeSetFields(builder, changeData);

            if (changeData.containsKey("subChanges")) {
                Object raw = changeData.get("subChanges");
                if (raw instanceof List<?> rawList) {
                    List<HistoryChange.SubChange> subChanges = new java.util.ArrayList<>();
                    for (Object entry : rawList) {
                        if (entry instanceof Map<?, ?> m) {
                            HistoryChange.SubChange subChange = new HistoryChange.SubChange(
                                    (String) m.get("predicate"),
                                    (String) m.get("oldValue"),
                                    (String) m.get("newValue"),
                                    (String) m.get("annotationProperty"),
                                    "true".equals(m.get("addition")));
                            subChange.setId(UUID.randomUUID().toString());
                            subChange.setCategory((String) m.get("category"));
                            subChange.setLanguage((String) m.get("language"));
                            subChange.setDatatype((String) m.get("datatype"));
                            subChanges.add(subChange);
                        }
                    }
                    builder.subChanges(subChanges);
                }
            }

            if (changeData.containsKey("timestamp")) {
                Object timestampObj = changeData.get("timestamp");
                if (timestampObj instanceof Long) {
                    LocalDateTime timestamp = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli((Long) timestampObj),
                        ZoneOffset.UTC
                    );
                    builder.timestamp(timestamp);
                }
            }

            HistoryChange historyChange = builder.build();
            applyExactFields(historyChange, changeData);
            historyChangeRepository.save(historyChange);

            log.info("Synced change {} to MongoDB for project {}", editId, projectId);
        } catch (Exception e) {
            log.error("Failed to sync change {} to MongoDB", editId, e);
        }
    }

    public void syncRecentChanges(String projectId, int count) {
        try {
            List<Map<String, Object>> recentChanges = historyService.getHistory(projectId, count);

            int syncedCount = 0;
            for (Map<String, Object> change : recentChanges) {
                String editId = (String) change.get("editId");
                if (editId != null && !historyChangeRepository.existsByProjectIdAndEditId(projectId, editId)) {
                    syncChange(projectId, editId, change);
                    syncedCount++;
                }
            }

            log.info("Synced {} changes for project {} (out of {} recent)", syncedCount, projectId, recentChanges.size());
        } catch (Exception e) {
            log.error("Failed to sync recent changes for project {}", projectId, e);
        }
    }

    public List<HistoryChange> getHistoryChanges(String projectId) {
        return historyChangeRepository.findByProjectIdOrderByTimestampDesc(projectId);
    }

    public List<HistoryChange> getHistoryChangesByStatus(String projectId, String status) {
        return historyChangeRepository.findByProjectIdAndStatusOrderByTimestampDesc(projectId, status);
    }

    private static void applyExactFields(HistoryChange change, Map<String, Object> changeData) {
        if (changeData.get("exactDeleted") instanceof String deleted) {
            change.setExactDeleted(deleted);
        }
        if (changeData.get("exactInserted") instanceof String inserted) {
            change.setExactInserted(inserted);
        }
        if (changeData.get("coveredBy") instanceof String coveredBy) {
            change.setCoveredBy(coveredBy);
        }
        if (changeData.get("documentStore") instanceof String store && changeData.get("documentId") instanceof String id) {
            change.setDocumentStore(store);
            change.setDocumentId(id);
            change.setDocumentBefore(changeData.get("documentBefore") instanceof String before ? before : null);
            change.setDocumentAfter(changeData.get("documentAfter") instanceof String after ? after : null);
        }
    }

    private static void applyChangeSetFields(HistoryChange.Builder builder, Map<String, Object> changeData) {
        if (changeData.get("changeSetId") instanceof String changeSetId) {
            builder.changeSetId(changeSetId);
        }
        if (changeData.get("source") instanceof String source) {
            builder.source(source);
        }
        if (changeData.get("ai") instanceof HistoryChange.AiInfo ai) {
            builder.ai(ai);
        }
        if (changeData.get("revertsChangeSetId") instanceof String reverts) {
            builder.metadata("revertsChangeSetId", reverts);
        }
        if (changeData.get("rollbackAuditId") instanceof String rollbackAuditId) {
            builder.metadata("rollbackAuditId", rollbackAuditId);
        }
    }

    public void save(HistoryChange change) {
        historyChangeRepository.save(change);
    }

    public List<HistoryChange> getChangeSet(String projectId, String changeSetId) {
        return historyChangeRepository.findByProjectIdAndChangeSetIdOrderByTimestampDesc(projectId, changeSetId);
    }

    public void setEntryReverted(String changeId, boolean reverted, String auditId) {
        mongoTemplate.updateFirst(
                Query.query(
                        Criteria.where("_id").is(changeId)),
                new Update()
                        .set("reverted", reverted).set("revertedAuditId", auditId),
                HistoryChange.class);
    }

    public void setSubChangesReverted(String changeId, Collection<String> subChangeIds, boolean reverted,
                                      String auditId) {
        if (subChangeIds == null || subChangeIds.isEmpty()) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(
                        Criteria.where("_id").is(changeId)),
                new Update()
                        .set("subChanges.$[sc].reverted", reverted)
                        .set("subChanges.$[sc].revertedAuditId", auditId)
                        .filterArray(Criteria.where("sc._id").in(subChangeIds)),
                HistoryChange.class);
    }

    public int backfillSubChangeIds() {
        int updated = 0;
        Query missing = Query.query(
                Criteria.where("subChanges").elemMatch(
                        Criteria.where("id").exists(false)));
        for (HistoryChange change : mongoTemplate.find(missing, HistoryChange.class)) {
            List<HistoryChange.SubChange> subChanges = change.getSubChanges();
            for (int i = 0; i < subChanges.size(); i++) {
                if (subChanges.get(i).getId() != null) {
                    continue;
                }
                Query exact = Query.query(
                        Criteria.where("_id").is(change.getId())
                                .and("subChanges." + i + ".id").exists(false));
                updated += (int) mongoTemplate.updateFirst(exact,
                        new Update()
                                .set("subChanges." + i + ".id", UUID.randomUUID().toString()),
                        HistoryChange.class).getModifiedCount();
            }
        }
        return updated;
    }

    public HistoryChange getHistoryChange(String changeId) {

        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {

            change = historyChangeRepository.findByEditId(changeId).orElse(null);
        }
        return change;
    }

    public boolean approveChange(String changeId, String userId, String username) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }

        change.setStatus("APPROVED");
        change.setApprovedBy(username);
        change.setApprovedAt(LocalDateTime.now(ZoneOffset.UTC));
        historyChangeRepository.save(change);

        log.info("Change {} approved by {}", changeId, username);
        return true;
    }

    public boolean rejectChange(String changeId, String userId, String username) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }

        change.setStatus("REJECTED");
        change.setRejectedBy(username);
        change.setRejectedAt(LocalDateTime.now(ZoneOffset.UTC));
        historyChangeRepository.save(change);

        log.info("Change {} rejected by {}", changeId, username);
        return true;
    }

    public boolean addComment(String changeId, String userId, String username, String text) {

        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {

            change = historyChangeRepository.findByEditId(changeId).orElse(null);
        }
        if (change == null) {
            log.warn("Change not found for comment: {}", changeId);
            return false;
        }

        String commentId = UUID.randomUUID().toString();
        HistoryChange.CommentEntry comment = new HistoryChange.CommentEntry(userId, username, text);
        change.getComments().put(commentId, comment);
        historyChangeRepository.save(change);

        log.info("Comment added to change {} by {}", changeId, username);
        return true;
    }

    public boolean resolveConflict(String changeId, String userId, String username, String resolution, String mergedValue) {
        HistoryChange change = historyChangeRepository.findById(changeId).orElse(null);
        if (change == null) {
            return false;
        }

        change.setHasConflict(false);
        change.setConflictResolution(
                "merge".equals(resolution) && mergedValue != null && !mergedValue.isBlank()
                        ? mergedValue
                        : resolution);
        change.setResolvedBy(username);
        change.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        historyChangeRepository.save(change);

        log.info("Conflict resolved for change {} by {}", changeId, username);
        return true;
    }

    public List<HistoryChange> getConflicts(String projectId) {
        List<HistoryChange> changes = getHistoryChanges(projectId);
        Map<String, ConflictMatch> conflicts = computeConflicts(changes);
        return changes.stream().filter(change -> conflicts.containsKey(change.getId())).toList();
    }

    public Map<String, ConflictMatch> computeConflicts(List<HistoryChange> changes) {
        Map<String, ConflictMatch> result = new HashMap<>();
        Map<String, List<HistoryChange>> byEntity = new HashMap<>();
        for (HistoryChange change : changes) {
            if (change.isDraft() || change.isReverted() || change.getEntityIRI() == null
                    || change.getTimestamp() == null) {
                continue;
            }
            byEntity.computeIfAbsent(change.getEntityIRI(), k -> new ArrayList<>()).add(change);
        }

        long windowMillis = Duration.ofHours(conflictWindowHours).toMillis();
        for (List<HistoryChange> group : byEntity.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparing(HistoryChange::getTimestamp));
            for (int i = 1; i < group.size(); i++) {
                HistoryChange earlier = group.get(i - 1);
                HistoryChange later = group.get(i);
                if (!isGenuineConflict(earlier, later, windowMillis)) {
                    continue;
                }
                result.put(later.getId(), conflictMatchFor(earlier));
                result.put(earlier.getId(), conflictMatchFor(later));
            }
        }
        return result;
    }

    private boolean isGenuineConflict(HistoryChange earlier, HistoryChange later, long windowMillis) {
        if (Objects.equals(earlier.getUserId(), later.getUserId()) || sameNamedPerson(earlier, later)
                || isUndoOrRedoRecord(earlier) || isUndoOrRedoRecord(later)) {
            return false;
        }
        if (earlier.getResolvedAt() != null || later.getResolvedAt() != null) {
            return false;
        }
        if (earlier.getAnnotationProperty() != null && later.getAnnotationProperty() != null
                && !earlier.getAnnotationProperty().equals(later.getAnnotationProperty())) {
            return false;
        }
        long diffMillis = Duration.between(earlier.getTimestamp(), later.getTimestamp()).toMillis();
        if (diffMillis < 0 || diffMillis > windowMillis) {
            return false;
        }
        if (Objects.equals(later.getOldValue(), earlier.getNewValue())) {
            return false;
        }
        return !Objects.equals(later.getNewValue(), earlier.getNewValue());
    }

    private static boolean sameNamedPerson(HistoryChange first, HistoryChange second) {
        String name = first.getUsername();
        return name != null && !name.isBlank() && !"anonymous".equalsIgnoreCase(name.trim())
                && name.trim().equalsIgnoreCase(second.getUsername() == null ? "" : second.getUsername().trim());
    }

    private static boolean isUndoOrRedoRecord(HistoryChange change) {
        String type = change.getOperationType();
        return type != null && (type.startsWith("ROLLBACK_") || type.startsWith("REDO_"));
    }

    private static ConflictMatch conflictMatchFor(HistoryChange change) {
        return new ConflictMatch(change.getId(), change.getUserId(), change.getUsername(),
                change.getNewValue(), change.getTimestamp());
    }
}
