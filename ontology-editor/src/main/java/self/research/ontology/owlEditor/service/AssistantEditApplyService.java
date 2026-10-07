package self.research.ontology.owlEditor.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.util.PerfPhases;

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

    public static final String PROPOSAL_NOT_FOUND = "PROPOSAL_NOT_FOUND";

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
    private final CodeViewRangeMatcher rangeMatcher;
    private final AssistantGraphWriter graphWriter;

    @Value("${assistant.apply.triple-patch.enabled:true}")
    private boolean triplePatchEnabled;

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
                                      CodeViewRangeMatcher rangeMatcher) {
        this(groupRepository, storageManager, spliceWriter, reimportPipeline, remapService, lockRegistry,
                syntaxValidator, referenceCoverageValidator, datasetService, operationService, recoveryLockService,
                null, null, rangeMatcher);
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
                                      AssistantSessionRepository sessionRepository,
                                      CodeViewRangeMatcher rangeMatcher) {
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
        this.rangeMatcher = rangeMatcher;
        this.graphWriter = new AssistantGraphWriter(operationService, reimportPipeline, datasetService,
                new AssistantApplyFailureHandler(groupRepository, operationService, reimportPipeline, recoveryLockService,
                        datasetService));
    }

    public ApplyResult applyGroup(String sessionId, String serverGroupId, String userEmail) {
        return applyGroup(sessionId, serverGroupId, userEmail, null);
    }

    public ApplyResult applyGroup(String sessionId, String serverGroupId, String userEmail, String summary) {
        Optional<AssistantEditGroupDocument> initial = groupRepository.findById(serverGroupId);
        if (initial.isEmpty() || !belongsTo(initial.get(), sessionId, userEmail)) {
            ApplyResult rejected = errorResult(PROPOSAL_NOT_FOUND, "Unknown or unauthorized proposal");
            audit(userEmail, null, sessionId, serverGroupId, rejected);
            return rejected;
        }
        String projectId = initial.get().getProjectId();

        PerfPhases perf = new PerfPhases();
        long lockRequestedAt = System.nanoTime();
        long[] lockAcquiredAt = {0L};
        ApplyResult result;
        try {
            result = lockRegistry.runExclusive(projectId, () -> {
                lockAcquiredAt[0] = System.nanoTime();
                perf.add("lockWait", (lockAcquiredAt[0] - lockRequestedAt) / 1_000_000);
                try {
                    return applyLocked(sessionId, serverGroupId, userEmail, perf, summary);
                } finally {
                    perf.add("lockHeld", (System.nanoTime() - lockAcquiredAt[0]) / 1_000_000);
                }
            });
        } catch (Exception e) {
            log.error("[Assistant] Unexpected failure applying group {}: {}", serverGroupId, e.getMessage(), e);
            result = errorResult("APPLY_FAILED", e.getMessage() != null ? e.getMessage() : "Apply failed");
        }
        audit(userEmail, projectId, sessionId, serverGroupId, result);
        perf.mark("audit");
        log.info("[Assistant] [PERF] apply project={} group={} edits={} outcome={} {}", projectId, serverGroupId,
                initial.get().getEdits() == null ? 0 : initial.get().getEdits().size(),
                result.isOk() ? "ok" : result.getErrorCode(), perf.summary());
        return result;
    }

    private void audit(String userEmail, String projectId, String sessionId, String groupId, ApplyResult result) {
        if (auditService == null) {
            return;
        }
        try {
            Optional<AssistantSessionDocument> session = ownSession(sessionId, userEmail);
            String provider = session.map(AssistantSessionDocument::getProvider).orElse(null);
            String model = session.map(AssistantSessionDocument::getModel).orElse(null);
            auditService.record(new AssistantAuditService.AssistantAuditEvent(userEmail, projectId, sessionId,
                    groupId, APPLY_OPERATION, result.getNewRevision(), provider, model,
                    result.isOk() ? "ok" : "failed", result.getErrorCode(), result.getMessage()));
        } catch (Exception e) {
            log.warn("[Assistant] Could not audit apply of group {}: {}", groupId, e.getMessage());
        }
    }

    private Optional<AssistantSessionDocument> ownSession(String sessionId, String userEmail) {
        return sessionRepository != null && sessionId != null
                ? sessionRepository.findById(sessionId)
                        .filter(found -> userEmail != null && userEmail.equals(found.getUserEmail()))
                : Optional.empty();
    }

    private ChangeOrigin aiOrigin(AssistantEditGroupDocument group, String userEmail, String summary) {
        Optional<AssistantSessionDocument> session = ownSession(group.getSessionId(), userEmail);
        return ChangeOrigin.ai(group.getId(), session.map(AssistantSessionDocument::getProvider).orElse(null),
                session.map(AssistantSessionDocument::getModel).orElse(null), group.getSessionId(), summary);
    }

    private static boolean belongsTo(AssistantEditGroupDocument group, String sessionId, String userEmail) {
        return userEmail != null && userEmail.equals(group.getUserEmail())
                && sessionId != null && sessionId.equals(group.getSessionId());
    }

    private static StorageManager.ContentScope scopeFor(AssistantEditGroupDocument group) {
        return group.isDraft() ? new StorageManager.ContentScope(true, group.getDraftUserId())
                               : StorageManager.ContentScope.publicScope();
    }

    private ApplyResult applyLocked(String sessionId, String serverGroupId, String userEmail, PerfPhases perf,
                                    String summary)
            throws IOException {
        AssistantEditGroupDocument group = groupRepository.findById(serverGroupId).orElse(null);
        if (group == null || !belongsTo(group, sessionId, userEmail)) {
            return errorResult(PROPOSAL_NOT_FOUND, "Unknown or unauthorized proposal");
        }

        ApplyResult rejected = rejectByStatus(group);
        if (rejected == null) {
            rejected = rejectIfProjectBlocked(group);
        }
        if (rejected != null) {
            return rejected;
        }

        StorageManager.ContentScope scope = scopeFor(group);
        SparqlQueryContext.setUserId(scope.userId());
        SparqlQueryContext.setWantsDraft(scope.draft());
        try {
            List<LineRangeSpliceWriter.SpliceEdit> spliceEdits = toSpliceEdits(group);
            perf.mark("preChecks");

            String conflictMessage = findConflict(group, spliceEdits, scope);
            perf.mark("conflictCheck");
            if (conflictMessage != null) {
                markConflict(group, conflictMessage);
                return errorResult("CONFLICT", conflictMessage);
            }

            Path sourceFile = scope.draft()
                    ? storageManager.resolveCodeViewFile(group.getProjectId(), group.getTargetPath(), scope)
                    : storageManager.ensureCodeViewFile(group.getProjectId(), group.getTargetPath());
            perf.mark("ensureSource");
            Path splicedFile = spliceWriter.splice(sourceFile, storageManager.extensionFor(group.getTargetPath()), spliceEdits);
            perf.mark("splice");

            try {
                AssistantGraphWriter.Outcome outcome = graphWriter.write(group, sourceFile, splicedFile, userEmail,
                        triplePatchEnabled, perf, aiOrigin(group, userEmail, summary));
                if (outcome.failure() != null) {
                    return outcome.failure();
                }
                return commitApplied(group, outcome.written(), perf);
            } finally {
                Files.deleteIfExists(splicedFile);
            }
        } finally {
            SparqlQueryContext.clear();
        }
    }

    private ApplyResult rejectByStatus(AssistantEditGroupDocument group) {
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
        return null;
    }

    private ApplyResult rejectIfProjectBlocked(AssistantEditGroupDocument group) {
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
        return null;
    }

    private ApplyResult commitApplied(AssistantEditGroupDocument group, AssistantGraphWriter.Written reimportResult,
                                      PerfPhases perf) {
        group.setStatus(AssistantEditGroupStatus.APPLIED);
        group.setAppliedRevision(reimportResult.sourceVersion());
        group.setAppliedAt(Instant.now());
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);

        List<AssistantEditGroupDocument> siblings = new ArrayList<>(groupRepository.findByProjectIdAndTargetPathAndStatus(
                group.getProjectId(), group.getTargetPath(), AssistantEditGroupStatus.PENDING));
        siblings.removeIf(sibling -> sibling.getId().equals(group.getId()));
        List<AssistantEditGroupDocument> shiftedOrStale = remapService.remap(group, siblings);
        List<AssistantEditGroupDocument> touched = reimportResult.cacheMatchesSubmittedContent()
                ? remapService.stampVerifiedPositions(siblings, shiftedOrStale, reimportResult.sourceVersion())
                : shiftedOrStale;
        if (!touched.isEmpty()) {
            groupRepository.saveAll(touched);
        }
        perf.mark("commitAndRemap");

        List<RemappedGroupInfo> remappedInfo = new ArrayList<>();
        for (AssistantEditGroupDocument sibling : siblings) {
            boolean stale = sibling.getStatus() == AssistantEditGroupStatus.STALE;
            remappedInfo.add(new RemappedGroupInfo(sibling.getId(), shiftedOrStale.contains(sibling), stale));
        }

        log.info("[Assistant] Applied group {} for project {}, revision {}, {} sibling group(s) shifted or stale",
                group.getId(), group.getProjectId(), reimportResult.sourceVersion(), shiftedOrStale.size());

        return ApplyResult.builder().ok(true).applied(true)
                .newRevision(reimportResult.sourceVersion())
                .remappedPendingGroups(remappedInfo)
                .appliedRanges(AssistantEditGroupRemapService.appliedRanges(group))
                .build();
    }

    private void markConflict(AssistantEditGroupDocument group, String message) {
        group.setStatus(AssistantEditGroupStatus.CONFLICT);
        group.setStaleReason(message);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
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
                                List<LineRangeSpliceWriter.SpliceEdit> spliceEdits,
                                StorageManager.ContentScope scope) throws IOException {
        Long versionAtPropose = group.getPublicGraphVersionAtPropose();
        long currentVersion = scope.draft() ? storageManager.resolveGraphVersion(group.getProjectId(), scope)
                                            : storageManager.getPublicGraphVersion(group.getProjectId());
        boolean versionUnchanged = versionAtPropose != null && currentVersion == versionAtPropose;
        Long verifiedAt = group.getPositionsVerifiedAtVersion();
        boolean positionsTrusted = versionUnchanged || (verifiedAt != null && currentVersion == verifiedAt);
        if (!positionsTrusted && group.getEdits().stream().anyMatch(e -> e.getLineCount() == 0)) {
            return "Document changed since this group was checked, and the position of its inserted lines "
                    + "can't be re-verified";
        }
        if (hasLiveMismatch(group, scope)) {
            return "Document changed since this group was checked";
        }
        if (versionUnchanged) {
            return null;
        }
        boolean syntaxOk = scope.draft()
                ? syntaxValidator.isValid(group.getProjectId(), group.getTargetPath(), spliceEdits, scope)
                : syntaxValidator.isValid(group.getProjectId(), group.getTargetPath(), spliceEdits);
        if (!syntaxOk) {
            return "Document changed since this group was checked, and the edit no longer parses against it";
        }
        boolean coverageOk = scope.draft()
                ? referenceCoverageValidator.check(group.getProjectId(), group.getTargetPath(),
                        toCoverageEdits(group), scope).covered()
                : referenceCoverageValidator.check(group.getProjectId(), group.getTargetPath(),
                        toCoverageEdits(group)).covered();
        if (!coverageOk) {
            return "Document changed since this group was checked, and the edit no longer covers every "
                    + "reference it needs to";
        }
        return null;
    }

    private boolean hasLiveMismatch(AssistantEditGroupDocument group, StorageManager.ContentScope scope) {
        List<CodeViewRangeMatcher.ExpectedRange> expected = group.getEdits().stream()
                .map(e -> new CodeViewRangeMatcher.ExpectedRange(e.getStartLine(), e.getLineCount(), e.getOriginalText()))
                .toList();
        return !rangeMatcher.allMatch(group.getProjectId(), group.getTargetPath(), expected, scope);
    }

    private ApplyResult idempotentReplay(AssistantEditGroupDocument group) {
        return ApplyResult.builder().ok(true).applied(true)
                .newRevision(group.getAppliedRevision())
                .remappedPendingGroups(List.of())
                .appliedRanges(List.of())
                .build();
    }

    private ApplyResult errorResult(String errorCode, String message) {
        return ApplyResult.builder().ok(false).errorCode(errorCode).message(message).build();
    }

    public record RemappedGroupInfo(String serverGroupId, boolean remapped, boolean stale) {}


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApplyResult {
        private boolean ok;
        private boolean applied;
        private Long newRevision;
        private List<RemappedGroupInfo> remappedPendingGroups;
        private List<AssistantEditGroupRemapService.AppliedRange> appliedRanges;
        private String errorCode;
        private String message;
    }
}
