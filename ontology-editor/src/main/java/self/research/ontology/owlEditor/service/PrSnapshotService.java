package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Service
public class PrSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PrSnapshotService.class);
    public static final String KEY_PREFIX = "pr-snapshot-";

    private final DraftSessionRepository sessions;
    private final SparqlDatasetService datasetService;
    private final DraftPublishMergeService mergeService;
    private final DraftPublishService publishService;
    private final ProjectWriteLockRegistry locks;
    private final DraftTrackingService draftTracking;
    private final DraftPullRequestRepository pullRequests;

    public record Frozen(String key, List<String> changeIds, List<String> changeSetIds, List<DraftChange> changes) {
        public Frozen(String key, List<String> changeIds, List<String> changeSetIds) {
            this(key, changeIds, changeSetIds, List.of());
        }
    }

    public PrSnapshotService(DraftSessionRepository sessions, SparqlDatasetService datasetService,
                             DraftPublishMergeService mergeService, DraftPublishService publishService,
                             ProjectWriteLockRegistry locks, DraftTrackingService draftTracking,
                             DraftPullRequestRepository pullRequests) {
        this.sessions = sessions;
        this.datasetService = datasetService;
        this.mergeService = mergeService;
        this.publishService = publishService;
        this.locks = locks;
        this.draftTracking = draftTracking;
        this.pullRequests = pullRequests;
    }

    public static String keyFor(String prId) {
        return KEY_PREFIX + prId;
    }

    public Frozen freeze(String projectId, String authorId, String prId) {
        String key = keyFor(prId);
        return locks.exclusive(projectId, () -> {
            DraftSession author = sessions.findByProjectIdAndUserId(projectId, authorId)
                    .orElseThrow(() -> new IllegalStateException("Your draft is not ready. Switch to Draft mode and wait for it to load, then raise the pull request again"));
            Optional<String> baseline = mergeService.baselineOf(projectId, authorId);
            if (baseline.isEmpty()) {
                throw new IllegalStateException("Your draft has no saved starting version, so it cannot be frozen for a pull request");
            }
            drop(projectId, prId);
            try {
                datasetService.copyDraftGraph(projectId, authorId, key);
                String path = mergeService.writeBaselineFor(projectId, key, baseline.get());
                DraftSession snapshot = new DraftSession(projectId, key, author.getBaselineMainRevision(),
                        author.getBaselineMainTripleCount());
                snapshot.setBaselineAt(author.getBaselineAt() == null ? LocalDateTime.now() : author.getBaselineAt());
                snapshot.setBaselineSnapshotPath(path);
                snapshot.setCopyStatus(DraftCopyStatus.READY);
                snapshot.setPrefixes(author.getPrefixes() == null ? null : new HashMap<>(author.getPrefixes()));
                sessions.save(snapshot);
                List<DraftChange> changes = draftTracking.getUnappliedDraftsForUser(projectId, authorId);
                List<String> changeIds = changes.stream().map(DraftChange::getId).toList();
                List<String> changeSetIds = draftTracking.draftChangeSetIds(projectId, authorId);
                log.info("[PR-SNAPSHOT] Froze the draft of {} for PR {} in project {} ({} changes)",
                        authorId, prId, projectId, changeIds.size());
                return new Frozen(key, changeIds, changeSetIds == null ? List.of() : changeSetIds, List.copyOf(changes));
            } catch (Exception e) {
                drop(projectId, prId);
                throw new IllegalStateException("Could not freeze your draft for the pull request: " + e.getMessage(), e);
            }
        });
    }

    public void drop(String projectId, String prId) {
        String key = keyFor(prId);
        try {
            publishService.clearBaseline(projectId, key);
        } catch (Exception e) {
            log.warn("[PR-SNAPSHOT] Could not remove the saved starting version of PR {}: {}", prId, e.getMessage());
        }
        try {
            datasetService.clearDraftGraph(projectId, key);
        } catch (Exception e) {
            log.warn("[PR-SNAPSHOT] Could not clear the frozen graph of PR {}: {}", prId, e.getMessage());
        }
    }

    public void dropAllForProject(String projectId) {
        for (var pr : pullRequests.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (pr.getSnapshotKey() != null) {
                drop(projectId, pr.getId());
            }
        }
    }
}
