package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Owns the AI Assistant's immutable-snapshot session: pins a project's revision at
 * creation time, and is the sole source of truth for the retrieval-attempt budget
 * shared across read_context and run_sparql (decremented atomically so concurrent
 * calls can't both succeed past zero).
 *
 * <p>Pins {@link ProjectMetadataService#getMutationVersion}, not
 * {@link MainGraphRevisionService} — the latter only advances on draft-publish
 * (DraftTrackingService's own workflow) and would miss changes from the general
 * mutation path or from a Code View save (ProjectLoadController.saveCodeViewAndSync
 * bumps mutationVersion, never mainGraphRevision), which is exactly the surface
 * this assistant reads and edits.
 */
@Slf4j
@Service
public class AssistantSessionService {

    private final AssistantSessionRepository sessionRepository;
    private final ProjectMetadataService metadataService;
    private final MongoTemplate mongoTemplate;

    @Value("${assistant.session.retrieval-attempts-default:5}")
    private int defaultRetrievalAttempts;

    @Value("${assistant.session.token-budget-default:8000}")
    private int defaultTokenBudget;

    @Value("${assistant.session.deadline-seconds:300}")
    private long deadlineSeconds;

    public AssistantSessionService(AssistantSessionRepository sessionRepository,
                                    ProjectMetadataService metadataService,
                                    MongoTemplate mongoTemplate) {
        this.sessionRepository = sessionRepository;
        this.metadataService = metadataService;
        this.mongoTemplate = mongoTemplate;
    }

    public AssistantSessionDocument createSession(String projectId, String userEmail, String documentPath,
                                                   String actionType, String actionContext) {
        long pinnedRevision = metadataService.getMutationVersion(projectId);
        Instant now = Instant.now();
        AssistantSessionDocument session = AssistantSessionDocument.builder()
                .projectId(projectId)
                .userEmail(userEmail)
                .documentPath(documentPath)
                .actionType(actionType)
                .actionContext(actionContext)
                .pinnedRevision(pinnedRevision)
                .status(AssistantSessionStatus.ACTIVE)
                .retrievalAttemptsRemaining(defaultRetrievalAttempts)
                .tokenBudgetRemaining(defaultTokenBudget)
                .expiresAt(now.plusSeconds(deadlineSeconds))
                .createdAt(now)
                .updatedAt(now)
                .build();
        AssistantSessionDocument saved = sessionRepository.save(session);
        log.info("[Assistant] Session {} created for project {} pinned at revision {}",
                saved.getId(), projectId, pinnedRevision);
        return saved;
    }

    /**
     * Returns the session only if it belongs to the caller, is ACTIVE, and hasn't passed
     * its deadline. A session found past its deadline is flipped to EXPIRED as a side
     * effect so it doesn't need a separate sweep — every caller that touches it eventually
     * observes and finalizes the expiry.
     */
    public Optional<AssistantSessionDocument> getActiveSession(String sessionId, String userEmail) {
        Optional<AssistantSessionDocument> found = sessionRepository.findByIdAndUserEmail(sessionId, userEmail);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AssistantSessionDocument session = found.get();
        if (session.getStatus() != AssistantSessionStatus.ACTIVE) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(session.getExpiresAt())) {
            expireSession(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Atomically decrements the shared retrieval budget only if it's still positive —
     * a plain read-then-write here would let two concurrent tool calls both succeed
     * past zero.
     */
    public boolean tryConsumeRetrievalAttempt(String sessionId) {
        Query query = Query.query(Criteria.where("_id").is(sessionId)
                .and("status").is(AssistantSessionStatus.ACTIVE)
                .and("retrievalAttemptsRemaining").gt(0));
        Update update = new Update()
                .inc("retrievalAttemptsRemaining", -1)
                .set("updatedAt", Instant.now());
        AssistantSessionDocument updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), AssistantSessionDocument.class);
        return updated != null;
    }

    public int getMaxRetrievalAttempts() {
        return defaultRetrievalAttempts;
    }

    public boolean isRevisionStale(AssistantSessionDocument session) {
        return metadataService.getMutationVersion(session.getProjectId()) != session.getPinnedRevision();
    }

    public void completeSession(String sessionId) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", AssistantSessionStatus.COMPLETED).set("updatedAt", Instant.now()),
                AssistantSessionDocument.class);
    }

    private void expireSession(String sessionId) {
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(sessionId)),
                new Update().set("status", AssistantSessionStatus.EXPIRED).set("updatedAt", Instant.now()),
                AssistantSessionDocument.class);
    }
}
