package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class AssistantApplyRecoveryReconciler {

    static final Duration FINISHED_OPERATION_RETENTION = Duration.ofDays(30);
    static final String INTERRUPTED_REASON = "The server stopped while an assistant apply was in progress, so the "
            + "project's source and graph may not match. Restore the pre-apply snapshot, or check the project and "
            + "clear the lock.";

    private final AssistantApplyOperationService operationService;
    private final ProjectRecoveryLockService recoveryLockService;
    private final AssistantEditGroupRepository groupRepository;
    private final ProjectWriteLockRegistry lockRegistry;

    public AssistantApplyRecoveryReconciler(AssistantApplyOperationService operationService,
                                            ProjectRecoveryLockService recoveryLockService,
                                            AssistantEditGroupRepository groupRepository,
                                            ProjectWriteLockRegistry lockRegistry) {
        this.operationService = operationService;
        this.recoveryLockService = recoveryLockService;
        this.groupRepository = groupRepository;
        this.lockRegistry = lockRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            ReconcileReport report = reconcile();
            if (report.lockedProjects() > 0 || report.removedSnapshots() > 0 || report.prunedOperations() > 0) {
                log.warn("[Recovery] Startup reconciliation: {} interrupted apply(s), {} project(s) locked, "
                                + "{} orphan snapshot(s) removed, {} old operation record(s) pruned",
                        report.interruptedOperations(), report.lockedProjects(), report.removedSnapshots(),
                        report.prunedOperations());
            }
        } catch (Exception e) {
            log.error("[Recovery] Startup reconciliation of assistant applies failed: {}", e.getMessage(), e);
        }
    }

    public ReconcileReport reconcile() {
        List<AssistantApplyOperationDocument> interrupted = operationService.findInterrupted();
        Set<String> lockedProjects = new HashSet<>();
        for (AssistantApplyOperationDocument operation : interrupted) {
            try {
                boolean locked = lockRegistry.runExclusive(operation.getProjectId(), () -> freeze(operation));
                if (locked) {
                    lockedProjects.add(operation.getProjectId());
                }
            } catch (Exception e) {
                log.error("[Recovery] Could not reconcile interrupted apply {} for project {}: {}",
                        operation.getId(), operation.getProjectId(), e.getMessage(), e);
            }
        }

        Set<String> keep = new HashSet<>();
        for (AssistantApplyOperationDocument unresolved : operationService.findAllUnresolved()) {
            keep.add(unresolved.getId());
        }
        int removedSnapshots = operationService.sweepOrphanSnapshots(keep);
        long pruned = 0;
        try {
            pruned = operationService.pruneFinishedOlderThan(FINISHED_OPERATION_RETENTION);
        } catch (Exception e) {
            log.warn("[Recovery] Could not prune old apply operation records: {}", e.getMessage());
        }
        return new ReconcileReport(interrupted.size(), lockedProjects.size(), removedSnapshots, pruned);
    }

    private boolean freeze(AssistantApplyOperationDocument operation) {
        log.error("[Recovery] Apply operation {} for project {} was left in {} by a previous run; locking the project",
                operation.getId(), operation.getProjectId(), operation.getStatus());
        operationService.markFailedAwaitingRecovery(operation, "Interrupted while " + operation.getStatus());
        groupRepository.findById(operation.getGroupId()).ifPresent(this::markRecoveryRequired);
        boolean canRestore = operationService.snapshotExists(operation);
        recoveryLockService.lock(operation.getProjectId(), INTERRUPTED_REASON, operation.getId(), canRestore);
        return true;
    }

    private void markRecoveryRequired(AssistantEditGroupDocument group) {
        if (group.getStatus() == AssistantEditGroupStatus.APPLIED) {
            return;
        }
        group.setStatus(AssistantEditGroupStatus.RECOVERY_REQUIRED);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
    }

    public record ReconcileReport(int interruptedOperations, int lockedProjects, int removedSnapshots,
                                  long prunedOperations) {}
}
