package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Stage;
import self.research.ontology.owlEditor.document.UndoIntentDocument.Status;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.UndoIntentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class UndoIntentService {

    static final Set<Status> UNRESOLVED = EnumSet.of(Status.PENDING, Status.INTERRUPTED, Status.NEEDS_ATTENTION);

    private final UndoIntentRepository repository;

    public UndoIntentService(UndoIntentRepository repository) {
        this.repository = repository;
    }

    public static String scopeKey(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<UndoIntentDocument> existing(String projectId, String opId) {
        if (opId == null || opId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByProjectIdAndOpId(projectId, opId);
    }

    public Optional<UndoIntentDocument> begin(String projectId, String opId, String direction, String actor,
                                              String scopeKey) {
        Instant now = Instant.now();
        UndoIntentDocument intent = UndoIntentDocument.builder()
                .projectId(projectId)
                .opId(opId == null || opId.isBlank() ? null : opId)
                .direction(direction)
                .actor(actor)
                .scopeKey(scopeKey)
                .status(Status.PENDING)
                .stage(Stage.STARTED)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            return Optional.of(repository.save(intent));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void advance(UndoIntentDocument intent, Stage stage) {
        if (intent.getStage() == null || stage.ordinal() > intent.getStage().ordinal()) {
            intent.setStage(stage);
            save(intent);
        }
    }

    public void attach(UndoIntentDocument intent, List<HistoryChange> entries, String undoAuditId) {
        intent.setChangeIds(entries.stream().map(HistoryChange::getId).toList());
        intent.setDraft(entries.stream().anyMatch(HistoryChange::isDraft));
        intent.setUndoAuditId(undoAuditId);
        save(intent);
    }

    public void attachAudit(UndoIntentDocument intent, String auditId) {
        intent.setAuditId(auditId);
        save(intent);
    }

    public void commit(UndoIntentDocument intent, String auditId, int appliedCount) {
        intent.setAppliedCount(appliedCount);
        settle(intent, Status.COMMITTED, auditId != null ? auditId : intent.getAuditId(), null);
    }

    public void fail(UndoIntentDocument intent, String reason) {
        settle(intent, Status.FAILED, null, reason);
    }

    public void needsAttention(UndoIntentDocument intent, String reason) {
        settle(intent, Status.NEEDS_ATTENTION, intent.getAuditId(), reason);
    }

    public List<UndoIntentDocument> unresolved(String projectId) {
        return repository.findByProjectIdAndStatusIn(projectId, UNRESOLVED);
    }

    public List<UndoIntentDocument> unresolvedEverywhere() {
        return repository.findByStatusIn(UNRESOLVED);
    }

    public Set<String> entriesNeedingAttention(String projectId) {
        Set<String> ids = new LinkedHashSet<>();
        for (UndoIntentDocument intent : repository.findByProjectIdAndStatusIn(projectId, EnumSet.of(Status.NEEDS_ATTENTION))) {
            if (intent.getChangeIds() != null) {
                ids.addAll(intent.getChangeIds());
            }
        }
        return ids;
    }

    private void settle(UndoIntentDocument intent, Status status, String auditId, String reason) {
        intent.setStatus(status);
        intent.setAuditId(auditId);
        intent.setFailureReason(reason);
        save(intent);
    }

    private void save(UndoIntentDocument intent) {
        try {
            intent.setUpdatedAt(Instant.now());
            repository.save(intent);
        } catch (RuntimeException e) {
            log.error("[UNDO] Could not update undo operation {}: {}", intent.getId(), e.getMessage(), e);
        }
    }
}
