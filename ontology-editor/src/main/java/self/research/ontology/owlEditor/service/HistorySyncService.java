package self.research.ontology.owlEditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistorySyncService {

    private final HistoryChangeRepository historyChangeRepository;
    private final MongoTemplate mongoTemplate;
    private final OntologyHistoryService historyService;

    public long markDraftsPublished(String projectId, String userId) {
        if (projectId == null || userId == null) {
            return 0;
        }
        var result = mongoTemplate.updateMulti(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(projectId)
                                .and("userId").is(userId).and("draft").is(true)),
                new org.springframework.data.mongodb.core.query.Update().set("draft", false),
                self.research.ontology.owlEditor.model.HistoryChange.class);
        if (result.getModifiedCount() > 0) {
            log.info("Marked {} draft history entries as published for project {} user {}",
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
        return historyChangeRepository.findByProjectIdAndHasConflictOrderByTimestampDesc(projectId, true);
    }
}
