package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;

import java.time.Instant;

@Slf4j
final class AssistantApplyFailureHandler {

    private final AssistantEditGroupRepository groupRepository;
    private final AssistantApplyOperationService operationService;
    private final CodeViewReimportPipeline reimportPipeline;
    private final ProjectRecoveryLockService recoveryLockService;
    private final SparqlDatasetService datasetService;

    AssistantApplyFailureHandler(AssistantEditGroupRepository groupRepository, AssistantApplyOperationService operationService,
                                 CodeViewReimportPipeline reimportPipeline, ProjectRecoveryLockService recoveryLockService,
                                 SparqlDatasetService datasetService) {
        this.groupRepository = groupRepository;
        this.operationService = operationService;
        this.reimportPipeline = reimportPipeline;
        this.recoveryLockService = recoveryLockService;
        this.datasetService = datasetService;
    }

    ApplyResult handle(AssistantEditGroupDocument group, AssistantApplyOperationDocument operation,
                                              Exception reimportEx) {
        String baseMessage = messageOf(reimportEx);
        log.error("[Assistant] Reimport failed for project {} while applying group {}; trying to roll back to the "
                + "pre-apply snapshot. Original error: {}", group.getProjectId(), group.getId(), baseMessage, reimportEx);

        String restoreFailure = tryRestore(group, operation);
        if (restoreFailure == null) {
            operationService.markRolledBack(operation, baseMessage, "AUTOMATIC_ROLLBACK");
            String message = "Apply failed and was rolled back: " + baseMessage + ". The project is back to how it "
                    + "was before this change, and this proposal was not applied.";
            markConflict(group, message);
            return errorResult("CONFLICT", message);
        }

        boolean graphNonEmpty = probeGraphNonEmpty(group.getProjectId());
        if (!graphNonEmpty) {
            log.error("[Assistant] CRITICAL: reimport failed for project {}, the rollback failed too ({}), and the "
                    + "graph now looks empty - GraphDB commits in batches for big documents, so a failure partway "
                    + "through can leave the graph cleared but not fully reloaded.", group.getProjectId(), restoreFailure);
        } else {
            log.error("[Assistant] Reimport failed for project {} and the rollback failed too ({}). The graph still has "
                    + "some content, but a reload interrupted mid-transaction can leave a graph non-empty and still "
                    + "wrong. Non-empty does not mean intact.", group.getProjectId(), restoreFailure);
        }
        operationService.markFailedAwaitingRecovery(operation, baseMessage + " (rollback failed: " + restoreFailure + ")");
        group.setStatus(AssistantEditGroupStatus.RECOVERY_REQUIRED);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
        lockProject(group.getProjectId(), operation, "An assistant apply failed and the automatic rollback didn't "
                + "complete: " + baseMessage);
        return errorResult("RECOVERY_REQUIRED", "Apply failed: " + baseMessage + ". The automatic rollback didn't "
                + "complete either, so the project's source, graph, and history may now be inconsistent and haven't "
                + "been verified. This proposal won't be applied again, and the project is locked for changes until "
                + "it is restored or the lock is cleared.");
    }

    private String tryRestore(AssistantEditGroupDocument group, AssistantApplyOperationDocument operation) {
        if (!operationService.snapshotExists(operation)) {
            return "the pre-apply snapshot is missing";
        }
        String projectId = group.getProjectId();
        try {
            if (group.isDraft()) {
                reimportPipeline.restoreSnapshot(projectId, operationService.snapshotOf(operation),
                        true, group.getDraftUserId());
            } else {
                reimportPipeline.restoreSnapshot(projectId, operationService.snapshotOf(operation));
            }
            return null;
        } catch (Exception restoreEx) {
            log.error("[Assistant] Rollback of project {} to snapshot {} failed: {}",
                    projectId, operation.getId(), restoreEx.getMessage(), restoreEx);
            return messageOf(restoreEx);
        }
    }

    private void lockProject(String projectId, AssistantApplyOperationDocument operation, String reason) {
        try {
            recoveryLockService.lock(projectId, reason, operation.getId(), operationService.snapshotExists(operation));
        } catch (Exception lockEx) {
            log.error("[Assistant] CRITICAL: could not record the recovery lock for project {} (operation {}): {}",
                    projectId, operation.getId(), lockEx.getMessage(), lockEx);
        }
    }

    private boolean probeGraphNonEmpty(String projectId) {
        try {
            SparqlDatasetService.CappedSparqlResult probe =
                    datasetService.execSelectCapped(projectId, "SELECT ?s WHERE { ?s ?p ?o } LIMIT 1", 5, 1, 1_000);
            return !probe.rows().isEmpty();
        } catch (Exception probeEx) {
            log.warn("[Assistant] Post-failure graph integrity probe itself failed for project {} - "
                    + "cannot confirm whether the graph is intact: {}", projectId, probeEx.getMessage());
            return true;
        }
    }

    private void markConflict(AssistantEditGroupDocument group, String message) {
        group.setStatus(AssistantEditGroupStatus.CONFLICT);
        group.setStaleReason(message);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
    }

    static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static ApplyResult errorResult(String errorCode, String message) {
        return ApplyResult.builder().ok(false).errorCode(errorCode).message(message).build();
    }
}
