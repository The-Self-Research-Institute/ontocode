package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PrHistoryBackfill {

    private final DraftPullRequestRepository pullRequests;
    private final HistorySyncService historySync;

    public PrHistoryBackfill(DraftPullRequestRepository pullRequests, HistorySyncService historySync) {
        this.pullRequests = pullRequests;
        this.historySync = historySync;
    }

    @Value("${ontocode.pr.backfill-on-startup:true}")
    private boolean onStartup = true;

    void setOnStartup(boolean onStartup) {
        this.onStartup = onStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!onStartup) {
            return;
        }
        try {
            run(false);
        } catch (RuntimeException e) {
            log.warn("[PR-BACKFILL] Could not tag the rows of earlier merged PRs: {}", e.getMessage());
        }
    }

    public Map<String, Object> run(boolean dryRun) {
        List<Map<String, Object>> touched = new ArrayList<>();
        long rows = 0;
        for (DraftPullRequest pr : pullRequests.findAll()) {
            boolean merged = pr.getStatus() == DraftPullRequest.Status.APPROVED
                    || pr.getStatus() == DraftPullRequest.Status.REVERTED;
            List<String> sets = pr.getPublishedChangeSetIds();
            if (!merged || sets == null || sets.isEmpty() || pr.getProjectId() == null) {
                continue;
            }
            long count = dryRun
                    ? historySync.countRowsToTag(pr.getProjectId(), sets)
                    : historySync.tagRowsOfPr(pr.getProjectId(), sets, pr.getId());
            if (count > 0) {
                rows += count;
                touched.add(Map.of("projectId", pr.getProjectId(), "prId", pr.getId(), "rows", count));
            }
        }
        log.info("[PR-BACKFILL] dryRun={} prs={} rows={}", dryRun, touched.size(), rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("prs", touched.size());
        result.put("rows", rows);
        result.put("touched", touched);
        return result;
    }
}
