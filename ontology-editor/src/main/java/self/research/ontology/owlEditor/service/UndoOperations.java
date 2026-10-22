package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Request;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class UndoOperations {

    private static final String DIFFERENT_REQUEST = "This operation id was already used for a different request";

    private final UndoIntentService intents;
    private final UndoReconciler reconciler;

    public UndoOperations(UndoIntentService intents, UndoReconciler reconciler) {
        this.intents = intents;
        this.reconciler = reconciler;
    }

    Result around(Request req, Supplier<Result> body) {
        if (req.dryRun()) {
            return body.get();
        }
        Set<String> blocked = settleEarlierOperations(req.projectId());
        String opId = req.actor().opId() == null || req.actor().opId().isBlank() ? UUID.randomUUID().toString() : req.actor().opId();
        String key = UndoIntentService.scopeKey(req.projectId(), req.actor().userId(), req.direction(), req.level(),
                req.changeSetId(), req.changeId(), req.subChangeId(), req.undoAuditId());
        Optional<UndoIntentDocument> started = intents.begin(req.projectId(), opId, req.direction(), req.actor().userId(), key);
        if (started.isEmpty()) {
            return replay(req, opId, key);
        }
        UndoIntentDocument intent = started.get();
        UndoOperationContext.start(intents, intent, blocked);
        try {
            Result result = body.get();
            settle(intent, result);
            return result;
        } catch (RuntimeException e) {
            intents.needsAttention(intent, "It stopped part-way: " + e.getMessage());
            throw e;
        } finally {
            UndoOperationContext.end();
        }
    }

    private Set<String> settleEarlierOperations(String projectId) {
        try {
            reconciler.resolveLocked(projectId);
            return intents.entriesNeedingAttention(projectId);
        } catch (RuntimeException e) {
            log.error("[UNDO] Could not check earlier undo operations of project {}: {}", projectId, e.getMessage(), e);
            return Set.of();
        }
    }

    private void settle(UndoIntentDocument intent, Result result) {
        if (result.ok()) {
            intents.commit(intent, result.auditId(), result.applied() == null ? 0 : result.applied().size());
        } else if (intent.getStage() == null || intent.getStage() == Stage.STARTED) {
            intents.fail(intent, result.message());
        } else {
            intents.needsAttention(intent, result.message() != null ? result.message() : "It stopped part-way");
        }
    }

    public Optional<Result> repeat(String projectId, String opId, String direction, String actor) {
        return intents.existing(projectId, opId).map(earlier -> direction.equals(earlier.getDirection())
                && actor != null && actor.equalsIgnoreCase(earlier.getActor())
                ? outcome(earlier, direction)
                : refused(direction, DIFFERENT_REQUEST));
    }

    private Result replay(Request req, String opId, String key) {
        UndoIntentDocument earlier = intents.existing(req.projectId(), opId).orElse(null);
        if (earlier == null) {
            return refused(req.direction(), "This request was already received");
        }
        if (earlier.getScopeKey() != null && !earlier.getScopeKey().equals(key)) {
            return refused(req.direction(), DIFFERENT_REQUEST);
        }
        return outcome(earlier, req.direction());
    }

    private static Result outcome(UndoIntentDocument earlier, String direction) {
        return switch (earlier.getStatus()) {
            case COMMITTED -> new Result(200, true, false, direction, earlier.getOpId(), List.of(), List.of(),
                    earlier.getAuditId(), "This request was already completed");
            case PENDING -> refused(direction, "This request is still running");
            default -> refused(direction, earlier.getFailureReason() != null ? earlier.getFailureReason()
                    : "This request did not finish");
        };
    }

    private static Result refused(String direction, String message) {
        return new Result(409, false, false, direction, null, List.of(), List.of(), null, message);
    }
}
