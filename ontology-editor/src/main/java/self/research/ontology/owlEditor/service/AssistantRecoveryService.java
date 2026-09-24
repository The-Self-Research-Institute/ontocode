package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AssistantRecoveryService {

    public static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    public static final String RESTORE_OPERATION = "recovery_restore";
    public static final String CLEAR_OPERATION = "recovery_clear";
    public static final String CLEAR_FAILED = "CLEAR_FAILED";
    static final String RESTORED_RESOLUTION = "USER_RESTORE";

    private final ProjectRecoveryLockService recoveryLockService;
    private final AssistantApplyOperationService operationService;
    private final CodeViewReimportPipeline reimportPipeline;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantAuditService auditService;

    public AssistantRecoveryService(ProjectRecoveryLockService recoveryLockService,
                                    AssistantApplyOperationService operationService,
                                    CodeViewReimportPipeline reimportPipeline,
                                    ProjectWriteLockRegistry lockRegistry,
                                    AssistantAuditService auditService) {
        this.recoveryLockService = recoveryLockService;
        this.operationService = operationService;
        this.reimportPipeline = reimportPipeline;
        this.lockRegistry = lockRegistry;
        this.auditService = auditService;
    }

    public RecoveryState state(String projectId) {
        Optional<ProjectRecoveryLockDocument> lock = recoveryLockService.findActiveLock(projectId);
        if (lock.isEmpty()) {
            return new RecoveryState(false, null, null, null, false);
        }
        ProjectRecoveryLockDocument active = lock.get();
        boolean canRestore = active.isCanRestore() && operationService.findById(active.getOperationId())
                .map(operationService::snapshotExists)
                .orElse(false);
        return new RecoveryState(true, active.getReason(), active.getLockedAt(), active.getOperationId(), canRestore);
    }

    public RecoveryOutcome restore(String projectId, String actor) {
        try {
            return lockRegistry.runExclusive(projectId, () -> restoreLocked(projectId, actor));
        } catch (Exception e) {
            log.error("[Recovery] Restore of project {} requested by {} failed: {}", projectId, actor,
                    e.getMessage(), e);
            audit(actor, projectId, RESTORE_OPERATION, null, null, "failed", RECOVERY_REQUIRED, messageOf(e));
            return RecoveryOutcome.failure("Restoring the project failed: " + messageOf(e)
                    + ". The project is still locked.");
        }
    }

    public RecoveryOutcome clear(String projectId, String actor) {
        try {
            return lockRegistry.runExclusive(projectId, () -> clearLocked(projectId, actor));
        } catch (Exception e) {
            log.error("[Recovery] Clearing the recovery lock of project {} requested by {} failed: {}", projectId,
                    actor, e.getMessage(), e);
            audit(actor, projectId, CLEAR_OPERATION, null, null, "failed", CLEAR_FAILED, messageOf(e));
            return new RecoveryOutcome(false, CLEAR_FAILED, "Clearing the recovery lock failed: " + messageOf(e));
        }
    }

    private RecoveryOutcome restoreLocked(String projectId, String actor) throws Exception {
        Optional<ProjectRecoveryLockDocument> lock = recoveryLockService.findActiveLock(projectId);
        if (lock.isEmpty()) {
            audit(actor, projectId, RESTORE_OPERATION, null, null, "ok", null, "not locked");
            return RecoveryOutcome.success();
        }
        String operationId = lock.get().getOperationId();
        Optional<AssistantApplyOperationDocument> operation = operationService.findById(operationId);
        if (!lock.get().isCanRestore() || operation.isEmpty() || !operationService.snapshotExists(operation.get())) {
            audit(actor, projectId, RESTORE_OPERATION, operation.map(AssistantApplyOperationDocument::getGroupId)
                    .orElse(null), null, "rejected", RECOVERY_REQUIRED, "snapshot missing for operation " + operationId);
            return RecoveryOutcome.failure("There's no saved copy of the project from before the failed apply, so it "
                    + "can't be restored automatically. Check the project and clear the lock instead.");
        }

        AssistantApplyOperationDocument op = operation.get();
        long revision;
        try {
            revision = reimportPipeline.restoreSnapshot(projectId, operationService.snapshotOf(op));
        } catch (Exception restoreEx) {
            log.error("[Recovery] Restoring project {} from the snapshot of operation {} failed: {}", projectId,
                    op.getId(), restoreEx.getMessage(), restoreEx);
            audit(actor, projectId, RESTORE_OPERATION, op.getGroupId(), null, "failed", RECOVERY_REQUIRED,
                    messageOf(restoreEx));
            return RecoveryOutcome.failure("Restoring the project from its saved copy failed: " + messageOf(restoreEx)
                    + ". The project is still locked; you can try again or clear the lock.");
        }

        operationService.markRolledBack(op, null, RESTORED_RESOLUTION);
        resolveOthers(projectId, op.getId());
        recoveryLockService.release(projectId, actor, "RESTORED");
        log.info("[Recovery] Project {} restored by {} from the snapshot of operation {}", projectId, actor, op.getId());
        audit(actor, projectId, RESTORE_OPERATION, op.getGroupId(), revision, "ok", null, "operation " + op.getId());
        return RecoveryOutcome.success();
    }

    private RecoveryOutcome clearLocked(String projectId, String actor) {
        boolean wasLocked = recoveryLockService.findActiveLock(projectId).isPresent();
        List<AssistantApplyOperationDocument> unresolved = operationService.findUnresolvedForProject(projectId);
        for (AssistantApplyOperationDocument operation : unresolved) {
            operationService.markClearedByUser(operation);
        }
        recoveryLockService.release(projectId, actor, "CLEARED");
        log.info("[Recovery] Recovery lock of project {} cleared by {} ({} unresolved operation(s) closed)",
                projectId, actor, unresolved.size());
        audit(actor, projectId, CLEAR_OPERATION, null, null, "ok", null,
                (wasLocked ? "lock released" : "not locked") + ", " + unresolved.size() + " operation(s) cleared");
        return RecoveryOutcome.success();
    }

    private void resolveOthers(String projectId, String restoredOperationId) {
        for (AssistantApplyOperationDocument other : operationService.findUnresolvedForProject(projectId)) {
            if (!other.getId().equals(restoredOperationId)) {
                operationService.markClearedByUser(other);
            }
        }
    }

    private void audit(String actor, String projectId, String operation, String groupId, Long revision,
                       String outcome, String errorCode, String detail) {
        try {
            auditService.record(new AssistantAuditService.AssistantAuditEvent(actor, projectId, null, groupId,
                    operation, revision, null, null, outcome, errorCode, detail));
        } catch (Exception e) {
            log.warn("[Recovery] Could not audit {} for project {}: {}", operation, projectId, e.getMessage());
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public record RecoveryState(boolean locked, String reason, Instant lockedAt, String operationId,
                                boolean canRestore) {}

    public record RecoveryOutcome(boolean ok, String errorCode, String message) {
        static RecoveryOutcome success() {
            return new RecoveryOutcome(true, null, null);
        }

        static RecoveryOutcome failure(String message) {
            return new RecoveryOutcome(false, RECOVERY_REQUIRED, message);
        }
    }
}
