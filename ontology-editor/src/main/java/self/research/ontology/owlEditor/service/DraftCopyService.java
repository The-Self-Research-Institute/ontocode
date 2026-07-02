package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.time.LocalDateTime;

/**
 * Manages copy-on-switch draft sessions.
 *
 * When a user switches to draft mode, this service:
 *   1. Blocks if an import is in progress for the project.
 *   2. Clears any stale draft graph.
 *   3. Persists a DraftSession with status=COPYING and the current main revision.
 *   4. Asynchronously copies main → draft via SPARQL INSERT WHERE.
 *   5. Marks status=READY when done (or FAILED on error).
 *
 * On publish, the caller uses SPARQL MOVE GRAPH draft → main (atomic).
 * On discard, the caller calls SparqlDatasetService.clearDraftGraph().
 */
@Slf4j
@Service
public class DraftCopyService {

    private final SparqlDatasetService datasetService;
    private final DraftSessionRepository sessionRepository;
    private final MainGraphRevisionService revisionService;
    private final ImportQueueManager importQueueManager;

    public DraftCopyService(SparqlDatasetService datasetService,
                            DraftSessionRepository sessionRepository,
                            MainGraphRevisionService revisionService,
                            ImportQueueManager importQueueManager) {
        this.datasetService = datasetService;
        this.sessionRepository = sessionRepository;
        this.revisionService = revisionService;
        this.importQueueManager = importQueueManager;
    }

    public record InitiateResult(
            boolean accepted,
            String reason,
            long tripleCount,
            long mainRevisionAtCopy
    ) {}

    /**
     * Begin a draft copy for the user. Returns immediately; copy runs in background.
     *
     * @return InitiateResult — check {@code accepted} before trusting the other fields.
     */
    public InitiateResult initiateCopy(String projectId, String userId) {
        var stats = importQueueManager.getQueueStats();
        if (stats.getActiveProjectIds() != null && stats.getActiveProjectIds().contains(projectId)) {
            return new InitiateResult(false,
                    "An import is in progress for this project. Please wait until it finishes before switching to draft mode.",
                    0, 0);
        }

        long tripleCount = datasetService.countMainGraphTriples(projectId);
        long revision = revisionService.getRevision(projectId);

        DraftSession session = sessionRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseGet(() -> new DraftSession(projectId, userId, revision, tripleCount));
        session.setBaselineMainRevision(revision);
        session.setBaselineMainTripleCount(tripleCount);
        session.setBaselineAt(LocalDateTime.now());
        session.setCopyStatus(DraftCopyStatus.COPYING);
        sessionRepository.save(session);

        datasetService.clearDraftGraph(projectId, userId);

        executeGraphCopyAsync(projectId, userId);

        log.info("[DRAFT-COPY] Initiated copy for project {} user {} — {} triples, revision {}",
                projectId, userId, tripleCount, revision);
        return new InitiateResult(true, null, tripleCount, revision);
    }

    @Async("draftCopyExecutor")
    void executeGraphCopyAsync(String projectId, String userId) {
        try {
            datasetService.copyMainGraphToDraft(projectId, userId);
            updateStatus(projectId, userId, DraftCopyStatus.READY);
            log.info("[DRAFT-COPY] Copy complete for project {} user {}", projectId, userId);
        } catch (Exception e) {
            log.error("[DRAFT-COPY] Copy failed for project {} user {}", projectId, userId, e);
            updateStatus(projectId, userId, DraftCopyStatus.FAILED);
        }
    }

    public DraftCopyStatus getStatus(String projectId, String userId) {
        return sessionRepository.findByProjectIdAndUserId(projectId, userId)
                .map(s -> s.getCopyStatus() != null ? s.getCopyStatus() : DraftCopyStatus.NOT_FOUND)
                .orElse(DraftCopyStatus.NOT_FOUND);
    }

    /** True when the user's copy-on-switch draft graph is ready for edits. */
    public boolean isReady(String projectId, String userId) {
        return sessionRepository.findByProjectIdAndUserId(projectId, userId)
                .map(s -> s.getCopyStatus() == DraftCopyStatus.READY)
                .orElse(false);
    }

    /**
     * Returns the main graph revision that was current when this draft copy was made.
     * Used by the publish path to detect if main has advanced since the copy (conflict).
     */
    public long getMainRevisionAtCopy(String projectId, String userId) {
        return sessionRepository.findByProjectIdAndUserId(projectId, userId)
                .map(DraftSession::getBaselineMainRevision)
                .orElse(-1L);
    }

    private void updateStatus(String projectId, String userId, DraftCopyStatus status) {
        sessionRepository.findByProjectIdAndUserId(projectId, userId).ifPresent(s -> {
            s.setCopyStatus(status);
            sessionRepository.save(s);
        });
        // Evict the short-lived TTL cache in SparqlDatasetService so the next read
        // immediately sees the new status instead of waiting for the TTL to expire.
        datasetService.evictDraftReadyCache(projectId, userId);
    }
}
