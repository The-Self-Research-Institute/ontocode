package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class DraftHistoryOrphanCleaner {

    private final HistoryChangeRepository history;
    private final DraftSessionRepository sessions;
    private final DraftTrackingService drafts;
    private final HistorySyncService historySync;

    public DraftHistoryOrphanCleaner(HistoryChangeRepository history, DraftSessionRepository sessions,
                                     DraftTrackingService drafts, HistorySyncService historySync) {
        this.history = history;
        this.sessions = sessions;
        this.drafts = drafts;
        this.historySync = historySync;
    }

    public Map<String, Object> run(boolean dryRun) {
        List<Map<String, Object>> orphans = new ArrayList<>();
        long orphanRows = 0;
        long marked = 0;
        for (HistoryChangeRepository.DraftGroup group : history.countLiveDraftGroups()) {
            String projectId = group.projectId();
            String userId = group.userId();
            if (projectId == null || userId == null || hasLiveDraft(projectId, userId)) {
                continue;
            }
            orphanRows += group.rows();
            orphans.add(Map.of("projectId", projectId, "userId", userId, "rows", group.rows()));
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

    private List<Map<String, Object>> sameNameUnderSeveralIds() {
        List<Map<String, Object>> found = new ArrayList<>();
        for (HistoryChangeRepository.SameNameGroup group : history.findNamesUnderSeveralUserIds()) {
            Set<String> ids = new LinkedHashSet<>(group.userIds());
            if (ids.size() > 1) {
                found.add(Map.of("projectId", String.valueOf(group.projectId()),
                        "username", String.valueOf(group.username()), "userIds", new ArrayList<>(ids)));
            }
        }
        return found;
    }
}
