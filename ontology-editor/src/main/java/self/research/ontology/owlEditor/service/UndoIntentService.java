package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.repository.UndoIntentRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class UndoIntentService {


    private final UndoIntentRepository repository;

    public UndoIntentService(UndoIntentRepository repository) {
        this.repository = repository;
    }

    public Optional<UndoIntentDocument> existing(String projectId, String opId) {
        if (opId == null || opId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByProjectIdAndOpId(projectId, opId);
    }

    public Optional<UndoIntentDocument> begin(String projectId, String opId, String direction, String actor,
                                              boolean draft, List<String> changeIds) {
        Instant now = Instant.now();
        UndoIntentDocument intent = UndoIntentDocument.builder()
                .projectId(projectId)
                .opId(opId == null || opId.isBlank() ? null : opId)
                .direction(direction)
                .actor(actor)
                .draft(draft)
                .changeIds(changeIds)
                .status(Status.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return Optional.of(repository.save(intent));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void commit(UndoIntentDocument intent, String auditId) {
        settle(intent, Status.COMMITTED, auditId, null);
    }

    public void fail(UndoIntentDocument intent, String reason) {
        settle(intent, Status.FAILED, null, reason);
    }

    private void settle(UndoIntentDocument intent, Status status, String auditId, String reason) {
        try {
            intent.setStatus(status);
            intent.setAuditId(auditId);
            intent.setFailureReason(reason);
            intent.setUpdatedAt(Instant.now());
            repository.save(intent);
        } catch (RuntimeException e) {
            log.error("[UNDO] Could not record intent {} as {}: {}", intent.getId(), status, e.getMessage(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            int interrupted = markInterrupted(Instant.now().minus(Duration.ofMinutes(Limit.INTENT_STALE_MINUTES.value())));
            if (interrupted > 0) {
                log.warn("[UNDO] {} undo/redo request(s) were interrupted by a restart; the ontology may hold a "
                        + "partly applied undo. Check the Change Assistant history for those changes.", interrupted);
            }
        } catch (RuntimeException e) {
            log.error("[UNDO] Startup reconciliation of undo intents failed: {}", e.getMessage(), e);
        }
    }

    public int markInterrupted(Instant olderThan) {
        List<UndoIntentDocument> stale = repository.findByStatusAndCreatedAtBefore(Status.PENDING, olderThan);
        for (UndoIntentDocument intent : stale) {
            settle(intent, Status.INTERRUPTED, null, "The server stopped before this finished");
        }
        return stale.size();
    }
}
