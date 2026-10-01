package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Slf4j
@Service
public class AssistantSessionService {

    public static final String SESSION_CREATE_OPERATION = "session_create";
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private static final int CREATE_LOCK_STRIPES = 64;

    public record SessionCreateOutcome(AssistantSessionDocument session, Integer retryAfterSeconds) {
        public boolean created() {
            return session != null;
        }

        public boolean isOk() {
            return created();
        }

        public String getErrorCode() {
            return created() ? null : RATE_LIMITED;
        }
    }

    private final ReentrantLock[] createLocks = new ReentrantLock[CREATE_LOCK_STRIPES];

    private final AssistantSessionRepository sessionRepository;
    private final ProjectMetadataService metadataService;
    private final MongoTemplate mongoTemplate;
    private final AssistantAuditService auditService;

    @Value("${assistant.session.retrieval-attempts-default:5}")
    private int defaultRetrievalAttempts;

    @Value("${assistant.session.token-budget-default:8000}")
    private int defaultTokenBudget;

    @Value("${assistant.session.deadline-seconds:300}")
    private long deadlineSeconds;

    @Value("${assistant.admission.session.max-active-per-user:20}")
    private int maxActiveSessionsPerUser;

    public AssistantSessionService(AssistantSessionRepository sessionRepository,
                                    ProjectMetadataService metadataService,
                                    MongoTemplate mongoTemplate) {
        this(sessionRepository, metadataService, mongoTemplate, null);
    }

    @Autowired
    public AssistantSessionService(AssistantSessionRepository sessionRepository,
                                    ProjectMetadataService metadataService,
                                    MongoTemplate mongoTemplate,
                                    AssistantAuditService auditService) {
        this.sessionRepository = sessionRepository;
        this.metadataService = metadataService;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        for (int i = 0; i < CREATE_LOCK_STRIPES; i++) {
            createLocks[i] = new ReentrantLock();
        }
    }

    public SessionCreateOutcome createSession(String projectId, String userEmail, String documentPath,
                                              String actionType, String actionContext,
                                              String provider, String model,
                                              Supplier<Optional<Integer>> rateAdmission) {
        ReentrantLock lock = createLocks[Math.floorMod(String.valueOf(userEmail).hashCode(), CREATE_LOCK_STRIPES)];
        lock.lock();
        try {
            Optional<Integer> retryAfter = rateAdmission == null ? Optional.empty() : rateAdmission.get();
            if (retryAfter.isEmpty()) {
                retryAfter = activeSessionLimitRetryAfter(userEmail);
            }
            if (retryAfter.isPresent()) {
                recordCreateRejected(userEmail, projectId, provider, model, RATE_LIMITED,
                        "retryAfterSeconds=" + retryAfter.get());
                return new SessionCreateOutcome(null, retryAfter.get());
            }
            return new SessionCreateOutcome(
                    createSession(projectId, userEmail, documentPath, actionType, actionContext, provider, model),
                    null);
        } finally {
            lock.unlock();
        }
    }

    public AssistantSessionDocument createSession(String projectId, String userEmail, String documentPath,
                                                   String actionType, String actionContext) {
        return createSession(projectId, userEmail, documentPath, actionType, actionContext, null, null);
    }

    public AssistantSessionDocument createSession(String projectId, String userEmail, String documentPath,
                                                   String actionType, String actionContext,
                                                   String provider, String model) {
        long pinnedRevision = metadataService.getMutationVersion(projectId);
        Instant now = Instant.now();
        AssistantSessionDocument session = AssistantSessionDocument.builder()
                .projectId(projectId)
                .userEmail(userEmail)
                .documentPath(documentPath)
                .actionType(actionType)
                .actionContext(actionContext)
                .provider(provider)
                .model(model)
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
        audit(new AssistantAuditService.AssistantAuditEvent(userEmail, projectId, saved.getId(), null,
                SESSION_CREATE_OPERATION, pinnedRevision, provider, model, "ok", null, actionType));
        return saved;
    }

    public void recordCreateRejected(String userEmail, String projectId, String provider, String model,
                                     String errorCode, String detail) {
        audit(new AssistantAuditService.AssistantAuditEvent(userEmail, projectId, null, null,
                SESSION_CREATE_OPERATION, null, provider, model, "rejected", errorCode, detail));
    }

    private void audit(AssistantAuditService.AssistantAuditEvent event) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.record(event);
        } catch (Exception e) {
            log.warn("[Assistant] Could not audit {}: {}", event.operation(), e.getMessage());
        }
    }

    public Optional<Integer> activeSessionLimitRetryAfter(String userEmail) {
        Instant now = Instant.now();
        Query active = Query.query(Criteria.where("userEmail").is(userEmail)
                .and("status").is(AssistantSessionStatus.ACTIVE)
                .and("expiresAt").gt(now));
        long activeCount = mongoTemplate.count(active, AssistantSessionDocument.class);
        if (activeCount < maxActiveSessionsPerUser) {
            return Optional.empty();
        }
        AssistantSessionDocument soonest = mongoTemplate.findOne(
                Query.of(active).with(Sort.by(Sort.Direction.ASC, "expiresAt")).limit(1),
                AssistantSessionDocument.class);
        long waitSeconds = soonest != null && soonest.getExpiresAt() != null
                ? Duration.between(now, soonest.getExpiresAt()).toSeconds() + 1
                : deadlineSeconds;
        log.info("[Assistant] User {} has {} active sessions (limit {}), rejecting session create",
                userEmail, activeCount, maxActiveSessionsPerUser);
        return Optional.of((int) Math.max(1, Math.min(waitSeconds, Math.max(1, deadlineSeconds))));
    }

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

    public boolean tryConsumeTokenBudget(String sessionId, int estimatedTokens) {
        Query query = Query.query(Criteria.where("_id").is(sessionId)
                .and("status").is(AssistantSessionStatus.ACTIVE)
                .and("tokenBudgetRemaining").gte(estimatedTokens));
        Update update = new Update()
                .inc("tokenBudgetRemaining", -estimatedTokens)
                .set("updatedAt", Instant.now());
        AssistantSessionDocument updated = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), AssistantSessionDocument.class);
        return updated != null;
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
