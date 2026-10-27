package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DraftHistoryOrphanCleaner {

    private final MongoTemplate mongo;
    private final DraftSessionRepository sessions;
    private final DraftTrackingService drafts;
    private final HistorySyncService historySync;

    public DraftHistoryOrphanCleaner(MongoTemplate mongo, DraftSessionRepository sessions,
                                     DraftTrackingService drafts, HistorySyncService historySync) {
        this.mongo = mongo;
        this.sessions = sessions;
        this.drafts = drafts;
        this.historySync = historySync;
    }

    public Map<String, Object> run(boolean dryRun) {
        List<Map<String, Object>> orphans = new ArrayList<>();
        long orphanRows = 0;
        long marked = 0;
        for (Document group : liveDraftGroups()) {
            Document id = group.get("_id", Document.class);
            String projectId = id.getString("projectId");
            String userId = id.getString("userId");
            long rows = ((Number) group.get("rows")).longValue();
            if (projectId == null || userId == null || hasLiveDraft(projectId, userId)) {
                continue;
            }
            orphanRows += rows;
            orphans.add(Map.of("projectId", projectId, "userId", userId, "rows", rows));
            if (!dryRun) {
                marked += historySync.markDraftsDiscarded(projectId, userId);
            }
        }
        log.info("[DRAFT-CLEANUP] dryRun={} orphanGroups={} orphanRows={} marked={}", dryRun, orphans.size(), orphanRows, marked);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("orphanGroups", orphans.size());
        result.put("orphanRows", orphanRows);
        result.put("marked", marked);
        result.put("orphans", orphans);
        result.put("sameNameUnderSeveralIds", sameNameUnderSeveralIds());
        return result;
    }

    private boolean hasLiveDraft(String projectId, String userId) {
        return sessions.findByProjectIdAndUserId(projectId, userId).isPresent()
                || !drafts.getUnappliedDraftsForUser(projectId, userId).isEmpty();
    }

    private List<Document> liveDraftGroups() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("draft").is(true).and("draftDiscarded").ne(true)),
                Aggregation.group("projectId", "userId").count().as("rows"));
        return mongo.aggregate(aggregation, HistoryChange.class, Document.class).getMappedResults();
    }

    private List<Map<String, Object>> sameNameUnderSeveralIds() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("username").ne(null)),
                Aggregation.group("projectId", "username").addToSet("userId").as("ids"));
        List<Map<String, Object>> found = new ArrayList<>();
        for (Document group : mongo.aggregate(aggregation, HistoryChange.class, Document.class).getMappedResults()) {
            Set<String> ids = new LinkedHashSet<>();
            for (Object id : (List<?>) group.get("ids")) {
                if (id != null) {
                    ids.add(id.toString());
                }
            }
            if (ids.size() > 1) {
                Document key = group.get("_id", Document.class);
                found.add(Map.of("projectId", String.valueOf(key.get("projectId")),
                        "username", String.valueOf(key.get("username")), "userIds", new ArrayList<>(ids)));
            }
        }
        return found;
    }
}
