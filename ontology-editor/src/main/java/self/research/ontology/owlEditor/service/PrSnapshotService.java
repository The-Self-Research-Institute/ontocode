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

    static final String ALTERNATE_SUFFIX = "-b";

    static String alternateKey(String prId, String previousKey) {
        return previousKey != null && previousKey.equals(keyFor(prId)) ? keyFor(prId) + ALTERNATE_SUFFIX : keyFor(prId);
    }

    public static String prIdOfKey(String key) {
        String id = key.substring(KEY_PREFIX.length());
        return id.endsWith(ALTERNATE_SUFFIX) ? id.substring(0, id.length() - ALTERNATE_SUFFIX.length()) : id;
    }

    public Frozen freeze(String projectId, String authorId, String prId) {
        return freeze(projectId, authorId, prId, null);
    }

    public Frozen freeze(String projectId, String authorId, String prId, String previousKey) {
        String key = alternateKey(prId, previousKey);
        return locks.exclusive(projectId, () -> {
            DraftSession author = sessions.findByProjectIdAndUserId(projectId, authorId)
                    .orElseThrow(() -> new IllegalStateException("Your draft is not ready. Switch to Draft mode and wait for it to load, then raise the pull request again"));
            Optional<String> baseline = mergeService.baselineOf(projectId, authorId);
            if (baseline.isEmpty()) {
                throw new IllegalStateException("Your draft has no saved starting version, so it cannot be frozen for a pull request");
            }
            dropKey(projectId, key);
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
                java.util.Set<String> changeSetIds = new java.util.LinkedHashSet<>(ownChangeSetIds(changes));
                List<String> fromHistory = draftTracking.draftChangeSetIds(projectId, authorId);
                if (fromHistory != null) {
                    changeSetIds.addAll(fromHistory);
                }
                log.info("[PR-SNAPSHOT] Froze the draft of {} for PR {} in project {} ({} changes)",
                        authorId, prId, projectId, changeIds.size());
                return new Frozen(key, changeIds, List.copyOf(changeSetIds), List.copyOf(changes));
            } catch (Exception e) {
                dropKey(projectId, key);
                throw new IllegalStateException("Could not freeze your draft for the pull request: " + e.getMessage(), e);
            }
        });
    }

    static List<String> ownChangeSetIds(List<DraftChange> changes) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (DraftChange change : changes) {
            Object listed = change.getOperationData() == null ? null : change.getOperationData().get("changeSetIds");
            if (listed instanceof java.util.Collection<?> collection) {
                collection.stream().filter(java.util.Objects::nonNull).map(Object::toString).forEach(ids::add);
            }
        }
        return List.copyOf(ids);
    }

    public void drop(String projectId, String prId) {
        dropKey(projectId, keyFor(prId));
        dropKey(projectId, keyFor(prId) + ALTERNATE_SUFFIX);
    }

    public void dropKey(String projectId, String key) {
        if (key == null) {
            return;
        }
        try {
            publishService.clearBaseline(projectId, key);
        } catch (Exception e) {
            log.warn("[PR-SNAPSHOT] Could not remove the saved starting version of copy {}: {}", key, e.getMessage());
        }
        try {
            datasetService.clearDraftGraph(projectId, key);
        } catch (Exception e) {
            log.warn("[PR-SNAPSHOT] Could not clear the frozen graph of copy {}: {}", key, e.getMessage());
        }
        sessions.findByProjectIdAndUserId(projectId, key).ifPresent(sessions::delete);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            sweepOrphans();
        } catch (RuntimeException e) {
            log.warn("[PR-SNAPSHOT] Could not sweep leftover copies: {}", e.getMessage());
        }
    }

    int sweepOrphans() {
        int dropped = 0;
        for (DraftSession session : sessions.findByUserIdStartingWith(KEY_PREFIX)) {
            String key = session.getUserId();
            var pr = pullRequests.findById(prIdOfKey(key)).orElse(null);
            boolean live = pr != null && pr.getStatus() == self.research.ontology.owlEditor.model.DraftPullRequest.Status.OPEN
                    && key.equals(pr.getSnapshotKey());
            if (!live) {
                dropKey(session.getProjectId(), key);
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("[PR-SNAPSHOT] Removed {} leftover pull request copies", dropped);
        }
        return dropped;
    }

    public void dropAllForProject(String projectId) {
        for (var pr : pullRequests.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            if (pr.getSnapshotKey() != null) {
                drop(projectId, pr.getId());
            }
        }
    }
}
