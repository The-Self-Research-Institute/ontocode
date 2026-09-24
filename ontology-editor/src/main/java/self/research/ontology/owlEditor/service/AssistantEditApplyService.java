package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AssistantEditApplyService {

    public static final String PROJECT_RECOVERY_LOCKED = "PROJECT_RECOVERY_LOCKED";
    public static final String APPLY_OPERATION = "apply";

    private final AssistantEditGroupRepository groupRepository;
    private final StorageManager storageManager;
    private final LineRangeSpliceWriter spliceWriter;
    private final CodeViewReimportPipeline reimportPipeline;
    private final AssistantEditGroupRemapService remapService;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantEditSyntaxValidator syntaxValidator;
    private final AssistantEditReferenceCoverageValidator referenceCoverageValidator;
    private final SparqlDatasetService datasetService;
    private final AssistantApplyOperationService operationService;
    private final ProjectRecoveryLockService recoveryLockService;
    private final AssistantAuditService auditService;
    private final AssistantSessionRepository sessionRepository;

    public AssistantEditApplyService(AssistantEditGroupRepository groupRepository,
                                      StorageManager storageManager,
                                      LineRangeSpliceWriter spliceWriter,
                                      CodeViewReimportPipeline reimportPipeline,
                                      AssistantEditGroupRemapService remapService,
                                      ProjectWriteLockRegistry lockRegistry,
                                      AssistantEditSyntaxValidator syntaxValidator,
                                      AssistantEditReferenceCoverageValidator referenceCoverageValidator,
                                      SparqlDatasetService datasetService,
                                      AssistantApplyOperationService operationService,
                                      ProjectRecoveryLockService recoveryLockService) {
        this(groupRepository, storageManager, spliceWriter, reimportPipeline, remapService, lockRegistry,
                syntaxValidator, referenceCoverageValidator, datasetService, operationService, recoveryLockService,
                null, null);
    }

    @Autowired
    public AssistantEditApplyService(AssistantEditGroupRepository groupRepository,
                                      StorageManager storageManager,
                                      LineRangeSpliceWriter spliceWriter,
                                      CodeViewReimportPipeline reimportPipeline,
                                      AssistantEditGroupRemapService remapService,
                                      ProjectWriteLockRegistry lockRegistry,
                                      AssistantEditSyntaxValidator syntaxValidator,
                                      AssistantEditReferenceCoverageValidator referenceCoverageValidator,
                                      SparqlDatasetService datasetService,
                                      AssistantApplyOperationService operationService,
                                      ProjectRecoveryLockService recoveryLockService,
                                      AssistantAuditService auditService,
                                      AssistantSessionRepository sessionRepository) {
        this.groupRepository = groupRepository;
        this.storageManager = storageManager;
        this.spliceWriter = spliceWriter;
        this.reimportPipeline = reimportPipeline;
        this.remapService = remapService;
        this.lockRegistry = lockRegistry;
        this.syntaxValidator = syntaxValidator;
        this.referenceCoverageValidator = referenceCoverageValidator;
        this.datasetService = datasetService;
        this.operationService = operationService;
        this.recoveryLockService = recoveryLockService;
        this.auditService = auditService;
        this.sessionRepository = sessionRepository;
    }

    public ApplyResult applyGroup(String sessionId, String serverGroupId, String userEmail) {
        Optional<AssistantEditGroupDocument> initial = groupRepository.findById(serverGroupId);
        if (initial.isEmpty() || !belongsTo(initial.get(), sessionId, userEmail)) {
            ApplyResult rejected = errorResult("VALIDATION_FAILED", "Unknown or unauthorized proposal");
            audit(userEmail, null, sessionId, serverGroupId, rejected);
            return rejected;
        }
        String projectId = initial.get().getProjectId();

        ApplyResult result;
        try {
            result = lockRegistry.runExclusive(projectId, () -> applyLocked(sessionId, serverGroupId, userEmail));
        } catch (Exception e) {
            log.error("[Assistant] Unexpected failure applying group {}: {}", serverGroupId, e.getMessage(), e);
            result = errorResult("APPLY_FAILED", e.getMessage() != null ? e.getMessage() : "Apply failed");
        }
        audit(userEmail, projectId, sessionId, serverGroupId, result);
        return result;
    }

    private void audit(String userEmail, String projectId, String sessionId, String groupId, ApplyResult result) {
        if (auditService == null) {
            return;
        }
        try {
            Optional<AssistantSessionDocument> session = sessionRepository != null && sessionId != null
                    ? sessionRepository.findById(sessionId)
                            .filter(found -> userEmail != null && userEmail.equals(found.getUserEmail()))
                    : Optional.empty();
            String provider = session.map(AssistantSessionDocument::getProvider).orElse(null);
            String model = session.map(AssistantSessionDocument::getModel).orElse(null);
            auditService.record(new AssistantAuditService.AssistantAuditEvent(userEmail, projectId, sessionId,
                    groupId, APPLY_OPERATION, result.getNewRevision(), provider, model,
                    result.isOk() ? "ok" : "failed", result.getErrorCode(), result.getMessage()));
        } catch (Exception e) {
            log.warn("[Assistant] Could not audit apply of group {}: {}", groupId, e.getMessage());
        }
    }

    private static boolean belongsTo(AssistantEditGroupDocument group, String sessionId, String userEmail) {
        return userEmail != null && userEmail.equals(group.getUserEmail())
                && sessionId != null && sessionId.equals(group.getSessionId());
    }

    private ApplyResult applyLocked(String sessionId, String serverGroupId, String userEmail) throws IOException {
        AssistantEditGroupDocument group = groupRepository.findById(serverGroupId).orElse(null);
        if (group == null || !belongsTo(group, sessionId, userEmail)) {
            return errorResult("VALIDATION_FAILED", "Unknown or unauthorized proposal");
        }

        switch (group.getStatus()) {
            case APPLIED:
                return idempotentReplay(group);
            case DISCARDED:
            case VALIDATION_FAILED:
                return errorResult("VALIDATION_FAILED", "Proposal is not applicable");
            case STALE:
                return errorResult("STALE_GROUP", group.getStaleReason());
            case CONFLICT:
                return errorResult("CONFLICT", group.getStaleReason() != null
                        ? group.getStaleReason() : "Document changed since this group was checked");
            case RECOVERY_REQUIRED:
                return errorResult("RECOVERY_REQUIRED", "An earlier attempt to apply this proposal failed partway "
                        + "through, so it won't be applied again. Check the project's state before making further changes.");
            case PENDING:
                break;
        }

        if (recoveryLockService.isLocked(group.getProjectId())) {
            return errorResult(PROJECT_RECOVERY_LOCKED, "This project is locked after a failed apply. Restore it "
                    + "or clear the lock before applying more changes.");
        }
        Optional<AssistantApplyOperationDocument> unresolved = operationService.findUnresolvedForGroup(group.getId());
        if (unresolved.isPresent()) {
            log.warn("[Assistant] Refusing to start a second import for group {}: operation {} is still {}",
                    group.getId(), unresolved.get().getId(), unresolved.get().getStatus());
            return errorResult("RECOVERY_REQUIRED", "An earlier attempt to apply this proposal hasn't been resolved "
                    + "yet, so it won't be started again. Check the project's state before making further changes.");
        }

        List<LineRangeSpliceWriter.SpliceEdit> spliceEdits = toSpliceEdits(group);

        String conflictMessage = findConflict(group, spliceEdits);
        if (conflictMessage != null) {
            markConflict(group, conflictMessage);
            return errorResult("CONFLICT", conflictMessage);
        }

        Path sourceFile = storageManager.ensureCodeViewFile(group.getProjectId(), group.getTargetPath());
        AssistantApplyOperationDocument operation;
        try {
            operation = operationService.prepare(group, userEmail);
        } catch (Exception snapshotEx) {
            log.error("[Assistant] Could not capture a pre-apply snapshot for project {}; not applying group {}: {}",
                    group.getProjectId(), group.getId(), snapshotEx.getMessage(), snapshotEx);
            return errorResult("APPLY_FAILED", "Couldn't save a copy of the project to roll back to, so the change "
                    + "wasn't applied. Nothing was modified; try again.");
        }

        Path splicedFile;
        try {
            String extension = storageManager.extensionFor(group.getTargetPath());
            splicedFile = spliceWriter.splice(sourceFile, extension, spliceEdits);
            operationService.markImporting(operation);
        } catch (IOException | RuntimeException beforeImportEx) {
            operationService.markAbandoned(operation, messageOf(beforeImportEx));
            throw beforeImportEx;
        }

        try {
            CodeViewReimportPipeline.ReimportResult reimportResult;
            try {
                reimportResult = reimportPipeline.reimport(
                        new CodeViewReimportPipeline.ReimportRequest(
                                group.getProjectId(), group.getTargetPath(), splicedFile, false,
                                userEmail, userEmail, null, operationService.snapshotOf(operation), true));
            } catch (Exception reimportEx) {
                return handleReimportFailure(group, operation, reimportEx);
            }

            operationService.markCommitted(operation);

            group.setStatus(AssistantEditGroupStatus.APPLIED);
            group.setAppliedRevision(reimportResult.sourceVersion());
            group.setAppliedAt(Instant.now());
            group.setUpdatedAt(Instant.now());
            groupRepository.save(group);

            List<AssistantEditGroupDocument> siblings = new ArrayList<>(groupRepository.findByProjectIdAndTargetPathAndStatus(
                    group.getProjectId(), group.getTargetPath(), AssistantEditGroupStatus.PENDING));
            siblings.removeIf(sibling -> sibling.getId().equals(group.getId()));
            List<AssistantEditGroupDocument> touched = remapService.remap(group, siblings);
            if (!touched.isEmpty()) {
                groupRepository.saveAll(touched);
            }

            List<RemappedGroupInfo> remappedInfo = new ArrayList<>();
            for (AssistantEditGroupDocument sibling : siblings) {
                remappedInfo.add(new RemappedGroupInfo(sibling.getId(), touched.contains(sibling)));
            }

            log.info("[Assistant] Applied group {} for project {}, revision {}, {} sibling group(s) touched",
                    group.getId(), group.getProjectId(), reimportResult.sourceVersion(), touched.size());

            return ApplyResult.builder().ok(true).applied(true)
                    .newRevision(reimportResult.sourceVersion())
                    .remappedPendingGroups(remappedInfo)
                    .build();
        } finally {
            Files.deleteIfExists(splicedFile);
        }
    }

    private ApplyResult handleReimportFailure(AssistantEditGroupDocument group, AssistantApplyOperationDocument operation,
                                              Exception reimportEx) {
        String baseMessage = messageOf(reimportEx);
        log.error("[Assistant] Reimport failed for project {} while applying group {}; trying to roll back to the "
                + "pre-apply snapshot. Original error: {}", group.getProjectId(), group.getId(), baseMessage, reimportEx);

        String restoreFailure = tryRestore(group.getProjectId(), operation);
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

    private String tryRestore(String projectId, AssistantApplyOperationDocument operation) {
        if (!operationService.snapshotExists(operation)) {
            return "the pre-apply snapshot is missing";
        }
        try {
            reimportPipeline.restoreSnapshot(projectId, operationService.snapshotOf(operation));
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

    private void markConflict(AssistantEditGroupDocument group, String message) {
        group.setStatus(AssistantEditGroupStatus.CONFLICT);
        group.setStaleReason(message);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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

    private List<LineRangeSpliceWriter.SpliceEdit> toSpliceEdits(AssistantEditGroupDocument group) {
        return group.getEdits().stream()
                .map(e -> new LineRangeSpliceWriter.SpliceEdit(e.getStartLine(), e.getLineCount(), e.getNewText()))
                .toList();
    }

    private List<AssistantEditReferenceCoverageValidator.CoverageEdit> toCoverageEdits(AssistantEditGroupDocument group) {
        return group.getEdits().stream()
                .map(e -> new AssistantEditReferenceCoverageValidator.CoverageEdit(
                        e.getStartLine(), e.getLineCount(), e.getOriginalText(), e.getNewText()))
                .toList();
    }

    private String findConflict(AssistantEditGroupDocument group,
                                List<LineRangeSpliceWriter.SpliceEdit> spliceEdits) throws IOException {
        Long versionAtPropose = group.getPublicGraphVersionAtPropose();
        boolean versionUnchanged = versionAtPropose != null
                && storageManager.getPublicGraphVersion(group.getProjectId()) == versionAtPropose;
        if (!versionUnchanged && group.getEdits().stream().anyMatch(e -> e.getLineCount() == 0)) {
            return "Document changed since this group was checked, and the position of its inserted lines "
                    + "can't be re-verified";
        }
        if (hasLiveMismatch(group)) {
            return "Document changed since this group was checked";
        }
        if (versionUnchanged) {
            return null;
        }
        if (!syntaxValidator.isValid(group.getProjectId(), group.getTargetPath(), spliceEdits)) {
            return "Document changed since this group was checked, and the edit no longer parses against it";
        }
        if (!referenceCoverageValidator.check(group.getProjectId(), group.getTargetPath(),
                toCoverageEdits(group)).covered()) {
            return "Document changed since this group was checked, and the edit no longer covers every "
                    + "reference it needs to";
        }
        return null;
    }

    private boolean hasLiveMismatch(AssistantEditGroupDocument group) throws IOException {
        for (EditEntry edit : group.getEdits()) {
            if (edit.getLineCount() == 0) {
                continue;
            }
            StorageManager.CodeViewPage page = storageManager.readCodeViewPage(
                    group.getProjectId(), group.getTargetPath(), edit.getStartLine(), edit.getLineCount());
            if (page == null || !page.content().equals(edit.getOriginalText())) {
                return true;
            }
        }
        return false;
    }

    private ApplyResult idempotentReplay(AssistantEditGroupDocument group) {
        return ApplyResult.builder().ok(true).applied(true)
                .newRevision(group.getAppliedRevision())
                .remappedPendingGroups(List.of())
                .build();
    }

    private ApplyResult errorResult(String errorCode, String message) {
        return ApplyResult.builder().ok(false).errorCode(errorCode).message(message).build();
    }

    public record RemappedGroupInfo(String serverGroupId, boolean remapped) {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyResult {
        private boolean ok;
        private boolean applied;
        private Long newRevision;
        private List<RemappedGroupInfo> remappedPendingGroups;
        private String errorCode;
        private String message;
    }
}
