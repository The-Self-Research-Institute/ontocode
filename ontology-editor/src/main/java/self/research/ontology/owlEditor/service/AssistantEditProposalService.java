package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.PerfPhases;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditGroupInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AssistantEditProposalService {

    public static final String PROPOSE_OPERATION = "edit_propose";
    public static final String RENAME_CHECK = "rename_occurrences_complete";
    public static final String SWRL_INSERT_CHECK = "swrl_inferred_axioms_resolved";
    public static final String FUZZY_INSERT_CHECK = "fuzzy_memberships_resolved";
    public static final String INSERTION_MOVED_CHECK = "insertion_moved_to_statement_boundary";

    private static final int MAX_AUDIT_DETAIL_CHARS = 500;

    private final AssistantSessionService sessionService;
    private final AssistantEditGroupRepository groupRepository;
    private final StorageManager storageManager;
    private final AssistantEditSyntaxValidator syntaxValidator;
    private final AssistantEditReferenceCoverageValidator referenceCoverageValidator;
    private final AssistantRenameService renameService;
    private final AssistantSwrlAxiomInsertionService swrlAxiomInsertionService;
    private final AssistantFuzzyMembershipInsertionService fuzzyMembershipInsertionService;
    private final AssistantEditSemanticValidator semanticValidator;
    private final ProjectWriteLockRegistry lockRegistry;
    private final AssistantAuditService auditService;
    private final AssistantInsertionSnapper insertionSnapper;

    @Value("${assistant.propose.max-edit-bytes:200000}")
    private int maxEditBytes;

    @Value("${assistant.propose.max-edits-per-group:20}")
    private int maxEditsPerGroup;

    @Value("${assistant.propose.max-rename-lines:5000}")
    private int maxRenameLines;

    @Value("${assistant.propose.max-swrl-axioms:200}")
    private int maxSwrlAxioms;

    @Value("${assistant.propose.max-fuzzy-memberships:50}")
    private int maxFuzzyMemberships;

    @Value("${assistant.propose.max-groups-per-request:10}")
    private int maxGroupsPerRequest;

    @Value("${assistant.edit-group.ttl-hours:24}")
    private long ttlHours;

    public AssistantEditProposalService(AssistantSessionService sessionService,
                                         AssistantEditGroupRepository groupRepository,
                                         StorageManager storageManager,
                                         AssistantEditSyntaxValidator syntaxValidator,
                                         AssistantEditReferenceCoverageValidator referenceCoverageValidator,
                                         AssistantRenameService renameService,
                                         AssistantSwrlAxiomInsertionService swrlAxiomInsertionService,
                                         AssistantFuzzyMembershipInsertionService fuzzyMembershipInsertionService,
                                         AssistantEditSemanticValidator semanticValidator,
                                         AssistantAuditService auditService,
                                         ProjectWriteLockRegistry lockRegistry,
                                         AssistantInsertionSnapper insertionSnapper) {
        this.sessionService = sessionService;
        this.groupRepository = groupRepository;
        this.storageManager = storageManager;
        this.syntaxValidator = syntaxValidator;
        this.referenceCoverageValidator = referenceCoverageValidator;
        this.renameService = renameService;
        this.swrlAxiomInsertionService = swrlAxiomInsertionService;
        this.fuzzyMembershipInsertionService = fuzzyMembershipInsertionService;
        this.semanticValidator = semanticValidator;
        this.auditService = auditService;
        this.lockRegistry = lockRegistry;
        this.insertionSnapper = insertionSnapper;
    }

    public ProposeEditResult propose(String sessionId, String userEmail, List<EditGroupInput> groups) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            audit(new AssistantAuditService.AssistantAuditEvent(userEmail, null, sessionId, null, PROPOSE_OPERATION,
                    null, null, null, "rejected", "SESSION_NOT_FOUND", "Session not found, expired, or not yours"));
            return ProposeEditResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (!"local-edit".equals(session.getActionType())) {
            return rejectRequest(session, "This session's action type does not allow proposing edits");
        }

        if (groups == null || groups.stream().anyMatch(Objects::isNull)) {
            return rejectRequest(session, "The proposal has no groups list, or one of its groups is empty");
        }

        if (groups.size() > maxGroupsPerRequest) {
            return rejectRequest(session, "Too many groups in one proposal (max " + maxGroupsPerRequest + ")");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlHours * 3600);

        List<GroupProposalOutcome> outcomes;
        try {
            outcomes = lockRegistry.runShared(session.getProjectId(), () -> {
                long publicGraphVersion = storageManager.getPublicGraphVersion(session.getProjectId());
                List<GroupProposalOutcome> proposed = new ArrayList<>();
                for (EditGroupInput groupInput : groups) {
                    proposed.add(proposeOneGroup(session, groupInput, publicGraphVersion, now, expiresAt));
                }
                return proposed;
            });
        } catch (Exception e) {
            log.warn("[Assistant] propose failed for session {}: {}", session.getId(), e.getMessage());
            return ProposeEditResult.builder().ok(false).errorCode("QUERY_ERROR")
                    .message(e.getMessage() != null ? e.getMessage() : "propose failed").build();
        }

        return ProposeEditResult.builder().ok(true).groups(outcomes).build();
    }

    private ProposeEditResult rejectRequest(AssistantSessionDocument session, String message) {
        audit(new AssistantAuditService.AssistantAuditEvent(session.getUserEmail(), session.getProjectId(),
                session.getId(), null, PROPOSE_OPERATION, session.getPinnedRevision(), session.getProvider(),
                session.getModel(), "rejected", "VALIDATION_FAILED", message));
        return ProposeEditResult.builder().ok(false).errorCode("VALIDATION_FAILED").message(message).build();
    }

    private GroupProposalOutcome proposeOneGroup(AssistantSessionDocument session, EditGroupInput groupInput,
                                                  long publicGraphVersion, Instant now, Instant expiresAt) {
        PerfPhases perf = new PerfPhases();
        List<CheckResult> checks = new ArrayList<>();
        List<EditInput> inputEdits = groupInput.edits() == null ? List.of() : groupInput.edits();
        boolean derived = groupInput.operation() != null;
        Set<String> introducedByOperation = Set.of();
        String renameSummary = null;
        if (derived) {
            OperationEdits operationEdits = deriveOperationEdits(session, groupInput, inputEdits, publicGraphVersion,
                    now, expiresAt, perf, checks);
            if (operationEdits.rejection() != null) {
                return operationEdits.rejection();
            }
            introducedByOperation = operationEdits.introducedIris();
            renameSummary = operationEdits.summary();
            inputEdits = operationEdits.edits();
        }
        if (inputEdits.stream().anyMatch(Objects::isNull)) {
            String path = inputEdits.stream().filter(Objects::nonNull).map(EditInput::targetPath)
                    .filter(Objects::nonNull).findFirst().orElse("");
            return rejectGroup(session, groupInput, path, publicGraphVersion, now, expiresAt,
                    new CheckResult("range_well_formed", false, "An edit in this group is empty."));
        }
        List<EditInput> sortedEdits = inputEdits.stream()
                .sorted(Comparator.comparingLong(e -> e.range() == null ? Long.MAX_VALUE : e.range().startLine()))
                .toList();
        String targetPath = sortedEdits.isEmpty() || sortedEdits.get(0).targetPath() == null
                ? "" : sortedEdits.get(0).targetPath();

        int maxDerivedEdits = maxEditsForOperation(groupInput.operation());
        boolean structurallySound = addStructuralChecks(session, targetPath, sortedEdits, derived, maxDerivedEdits, checks);
        perf.mark("structuralAndLiveMatch");
        sortedEdits = addContentChecks(session, targetPath, sortedEdits, structurallySound, derived,
                introducedByOperation, checks, perf);
        boolean passed = checks.stream().allMatch(CheckResult::passed);

        List<EditEntry> editEntries = sortedEdits.stream().map(ProposedEdits::toEditEntry).toList();
        List<DiffEntry> diff = ProposedEdits.toDiffEntries(sortedEdits);

        String summary = renameSummary != null ? renameSummary
                : sortedEdits.size() + " edit" + (sortedEdits.size() == 1 ? "" : "s") + " on " + targetPath;
        GroupProposalOutcome outcome = persistGroup(session, groupInput, targetPath, editEntries, passed,
                publicGraphVersion, now, expiresAt, checks, diff, summary);
        perf.mark("persist");
        log.info("[Assistant] [PERF] propose project={} session={} format={} edits={} rename={} passed={} {}",
                session.getProjectId(), session.getId(), targetPath, sortedEdits.size(), derived, passed,
                perf.summary());
        return outcome;
    }

    private record OperationEdits(List<EditInput> edits, Set<String> introducedIris, String summary,
                                  GroupProposalOutcome rejection) {}

    private OperationEdits deriveOperationEdits(AssistantSessionDocument session, EditGroupInput groupInput,
                                                List<EditInput> inputEdits, long publicGraphVersion, Instant now,
                                                Instant expiresAt, PerfPhases perf, List<CheckResult> checks) {
        EditOperation operation = groupInput.operation();
        String operationPath = operation.targetPath() == null ? "" : operation.targetPath();
        if (!inputEdits.isEmpty()) {
            return new OperationEdits(null, null, null,
                    rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                            new CheckResult(RENAME_CHECK, false,
                                    "A group can carry either explicit edits or an operation, not both.")));
        }
        if (AssistantSwrlAxiomInsertionService.ADD_INFERRED_AXIOMS.equals(operation.type())) {
            return deriveSwrlInsertionEdits(session, groupInput, operation, operationPath, publicGraphVersion, now,
                    expiresAt, perf, checks);
        }
        if (AssistantFuzzyMembershipInsertionService.ADD_FUZZY_MEMBERSHIP.equals(operation.type())) {
            return deriveFuzzyMembershipEdits(session, groupInput, operation, operationPath, publicGraphVersion, now,
                    expiresAt, perf, checks);
        }
        AssistantRenameService.RenameDerivation derivation =
                renameService.derive(session.getProjectId(), operation, maxRenameLines);
        perf.mark("renameDerive");
        if (!derivation.ok()) {
            return new OperationEdits(null, null, null,
                    rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                            new CheckResult(RENAME_CHECK, false, derivation.detail())));
        }
        checks.add(new CheckResult(RENAME_CHECK, true, derivation.detail()));
        String summary = AssistantRenameService.RENAME_IDENTIFIER + " <" + derivation.targetIri() + "> -> <"
                + derivation.replacementIri() + ">, " + derivation.occurrences() + " occurrences";
        List<EditInput> edits = derivation.edits().stream()
                .map(e -> new EditInput(operation.targetPath(), new EditRange(e.line(), 1), e.originalText(),
                        e.newText()))
                .toList();
        return new OperationEdits(edits, Set.of(derivation.replacementIri()), summary, null);
    }

    private OperationEdits deriveSwrlInsertionEdits(AssistantSessionDocument session, EditGroupInput groupInput,
                                                     EditOperation operation, String operationPath,
                                                     long publicGraphVersion, Instant now, Instant expiresAt,
                                                     PerfPhases perf, List<CheckResult> checks) {
        AssistantSwrlAxiomInsertionService.InsertionDerivation derivation =
                swrlAxiomInsertionService.derive(session.getProjectId(), operation, maxSwrlAxioms);
        perf.mark("swrlAxiomInsertionDerive");
        if (!derivation.ok()) {
            return new OperationEdits(null, null, null,
                    rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                            new CheckResult(SWRL_INSERT_CHECK, false, derivation.detail())));
        }
        checks.add(new CheckResult(SWRL_INSERT_CHECK, true, derivation.detail()));
        List<EditInput> edits = derivation.edits().stream()
                .map(e -> new EditInput(operation.targetPath(), new EditRange(e.line(), 0), e.originalText(),
                        e.newText()))
                .toList();
        return new OperationEdits(edits, Set.of(), derivation.detail(), null);
    }

    private OperationEdits deriveFuzzyMembershipEdits(AssistantSessionDocument session, EditGroupInput groupInput,
                                                       EditOperation operation, String operationPath,
                                                       long publicGraphVersion, Instant now, Instant expiresAt,
                                                       PerfPhases perf, List<CheckResult> checks) {
        AssistantFuzzyMembershipInsertionService.InsertionDerivation derivation =
                fuzzyMembershipInsertionService.derive(session.getProjectId(), operation, maxFuzzyMemberships);
        perf.mark("fuzzyMembershipInsertionDerive");
        if (!derivation.ok()) {
            return new OperationEdits(null, null, null,
                    rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                            new CheckResult(FUZZY_INSERT_CHECK, false, derivation.detail())));
        }
        checks.add(new CheckResult(FUZZY_INSERT_CHECK, true, derivation.detail()));
        List<EditInput> edits = derivation.edits().stream()
                .map(e -> new EditInput(operation.targetPath(), new EditRange(e.line(), 0), e.originalText(),
                        e.newText()))
                .toList();
        return new OperationEdits(edits, Set.of(), derivation.detail(), null);
    }

    private int maxEditsForOperation(EditOperation operation) {
        if (operation != null && AssistantSwrlAxiomInsertionService.ADD_INFERRED_AXIOMS.equals(operation.type())) {
            return maxSwrlAxioms;
        }
        if (operation != null && AssistantFuzzyMembershipInsertionService.ADD_FUZZY_MEMBERSHIP.equals(operation.type())) {
            return maxFuzzyMemberships;
        }
        return maxRenameLines;
    }

    private boolean addStructuralChecks(AssistantSessionDocument session, String targetPath,
                                        List<EditInput> sortedEdits, boolean derived, int maxDerivedEdits,
                                        List<CheckResult> checks) {
        boolean hasEdits = !sortedEdits.isEmpty();
        checks.add(new CheckResult("has_edits", hasEdits));

        boolean singleTargetPath = !targetPath.isBlank()
                && sortedEdits.stream().map(EditInput::targetPath).distinct().count() <= 1;
        checks.add(new CheckResult("single_target_path", singleTargetPath || !hasEdits));

        boolean rangeWellFormed = sortedEdits.stream().allMatch(ProposedEdits::isRangeWellFormed);
        checks.add(new CheckResult("range_well_formed", rangeWellFormed));

        boolean noOverlap = sortedEdits.size() <= 1 || ProposedEdits.hasNoIntraGroupOverlap(sortedEdits);
        checks.add(new CheckResult("no_intra_group_overlap", noOverlap));

        int maxEdits = derived ? maxDerivedEdits : maxEditsPerGroup;
        boolean sizeOk = sortedEdits.size() <= maxEdits
                && sortedEdits.stream().allMatch(e -> e.newText() != null && e.newText().length() <= maxEditBytes);
        checks.add(new CheckResult("size_limits", sizeOk));

        String projectId = session.getProjectId();
        boolean liveMatch = singleTargetPath && rangeWellFormed
                && (derived ? noOverlap
                        && ProposedEdits.matchesLiveContentInOnePass(storageManager, projectId, targetPath, sortedEdits)
                : sortedEdits.stream().allMatch(e -> ProposedEdits.matchesLiveContent(storageManager, projectId, e)));
        checks.add(new CheckResult("original_text_matches_live", liveMatch));

        return hasEdits && singleTargetPath && rangeWellFormed && noOverlap && sizeOk && liveMatch;
    }

    private List<EditInput> addContentChecks(AssistantSessionDocument session, String targetPath,
                                             List<EditInput> proposedEdits, boolean structurallySound,
                                             boolean derived, Set<String> introducedByOperation,
                                             List<CheckResult> checks, PerfPhases perf) {
        List<EditInput> sortedEdits = proposedEdits;
        CheckResult syntax;
        if (structurallySound) {
            AssistantEditSyntaxValidator.SyntaxResult result = syntaxValidator.check(session.getProjectId(),
                    targetPath, ProposedEdits.toSpliceEdits(sortedEdits));
            if (!result.valid() && !derived) {
                SnappedInsert snapped = snapInsertToStatementBoundary(session.getProjectId(), targetPath, sortedEdits);
                if (snapped != null) {
                    sortedEdits = snapped.edits();
                    result = snapped.syntax();
                    checks.add(snapped.note());
                }
            }
            syntax = new CheckResult("syntax_valid", result.valid(), result.detail());
        } else {
            syntax = new CheckResult("syntax_valid", true);
        }
        checks.add(syntax);
        perf.mark("syntax");

        CheckResult referenceCoverage = !structurallySound
                ? new CheckResult("complete_reference_coverage", true)
                : toCheckResult(referenceCoverageValidator.check(
                        session.getProjectId(), targetPath, ProposedEdits.toCoverageEdits(sortedEdits)));
        checks.add(referenceCoverage);
        perf.mark("referenceCoverage");

        if (!structurallySound) {
            checks.addAll(AssistantEditSemanticValidator.skipped(
                    "Skipped because the group failed its structural checks."));
        } else if (!syntax.passed()) {
            checks.addAll(AssistantEditSemanticValidator.skipped(
                    "Skipped because the edited document does not parse."));
        } else {
            checks.addAll(semanticValidator.check(session.getProjectId(), targetPath,
                    ProposedEdits.toSemanticEdits(sortedEdits), introducedByOperation));
        }

        perf.mark("semantic");
        return sortedEdits;
    }

    private record SnappedInsert(List<EditInput> edits, AssistantEditSyntaxValidator.SyntaxResult syntax,
                                 CheckResult note) {}

    private SnappedInsert snapInsertToStatementBoundary(String projectId, String targetPath, List<EditInput> edits) {
        long insertLine = edits.get(0).range().startLine();
        boolean singleInsertPoint = edits.stream()
                .allMatch(e -> e.range().lineCount() == 0 && e.range().startLine() == insertLine);
        if (!singleInsertPoint || !insertionSnapper.supports(targetPath)) {
            return null;
        }
        OptionalLong boundary = insertionSnapper.nextStatementBoundary(projectId, targetPath, insertLine);
        if (boundary.isEmpty()) {
            return null;
        }
        List<EditInput> moved = edits.stream()
                .map(e -> new EditInput(e.targetPath(), new EditRange(boundary.getAsLong(), 0), e.originalText(),
                        e.newText()))
                .toList();
        if (!syntaxValidator.regionParses(projectId, targetPath, ProposedEdits.toSpliceEdits(moved))) {
            return null;
        }
        AssistantEditSyntaxValidator.SyntaxResult retry = new AssistantEditSyntaxValidator.SyntaxResult(true, null);
        log.info("[Assistant] Moved insertion in {} from line {} to {} to avoid splitting a statement",
                targetPath, insertLine + 1, boundary.getAsLong() + 1);
        return new SnappedInsert(moved, retry, new CheckResult(INSERTION_MOVED_CHECK, true,
                "Moved from line " + (insertLine + 1) + " to line " + (boundary.getAsLong() + 1)
                        + " so it doesn't split a statement."));
    }

    private GroupProposalOutcome rejectGroup(AssistantSessionDocument session, EditGroupInput groupInput,
                                             String targetPath, long publicGraphVersion, Instant now,
                                             Instant expiresAt, CheckResult failedCheck) {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(failedCheck);
        String summary = groupInput.operation() != null && groupInput.operation().type() != null
                ? groupInput.operation().type() + " on " + targetPath
                : "group on " + targetPath;
        return persistGroup(session, groupInput, targetPath, List.of(), false, publicGraphVersion, now, expiresAt,
                checks, List.of(), summary);
    }

    private GroupProposalOutcome persistGroup(AssistantSessionDocument session, EditGroupInput groupInput,
                                              String targetPath, List<EditEntry> editEntries, boolean passed,
                                              long publicGraphVersion, Instant now, Instant expiresAt,
                                              List<CheckResult> checks, List<DiffEntry> diff, String summary) {
        AssistantEditGroupDocument document = AssistantEditGroupDocument.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(session.getId())
                .projectId(session.getProjectId())
                .userEmail(session.getUserEmail())
                .clientGroupId(groupInput.clientGroupId())
                .targetPath(targetPath)
                .edits(editEntries)
                .status(passed ? AssistantEditGroupStatus.PENDING : AssistantEditGroupStatus.VALIDATION_FAILED)
                .publicGraphVersionAtPropose(publicGraphVersion)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(expiresAt)
                .build();
        groupRepository.save(document);

        log.info("[Assistant] Proposed group {} for session {} status={}",
                document.getId(), session.getId(), document.getStatus());

        audit(new AssistantAuditService.AssistantAuditEvent(session.getUserEmail(), session.getProjectId(),
                session.getId(), document.getId(), PROPOSE_OPERATION, publicGraphVersion, session.getProvider(),
                session.getModel(), passed ? "pending" : "validation_failed", passed ? null : "VALIDATION_FAILED",
                auditDetail(summary, checks)));

        return GroupProposalOutcome.builder()
                .clientGroupId(groupInput.clientGroupId())
                .serverGroupId(document.getId())
                .validationPassed(passed)
                .checks(checks)
                .diff(diff)
                .build();
    }

    private String auditDetail(String summary, List<CheckResult> checks) {
        String failed = checks.stream().filter(c -> !c.passed()).map(CheckResult::name)
                .collect(Collectors.joining(", "));
        String detail = failed.isEmpty() ? summary : summary + "; failed: " + failed;
        return detail.length() <= MAX_AUDIT_DETAIL_CHARS ? detail : detail.substring(0, MAX_AUDIT_DETAIL_CHARS);
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

    private CheckResult toCheckResult(AssistantEditReferenceCoverageValidator.CoverageResult result) {
        return new CheckResult("complete_reference_coverage", result.covered(), result.detail());
    }

    public record CheckResult(String name, boolean passed, String detail) {
        public CheckResult(String name, boolean passed) {
            this(name, passed, null);
        }
    }

    public record DiffEntry(String targetPath, String before, String after, Long startLine, Integer lineCount) {
        public DiffEntry(String targetPath, String before, String after) {
            this(targetPath, before, after, null, null);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupProposalOutcome {
        private String clientGroupId;
        private String serverGroupId;
        private boolean validationPassed;
        private List<CheckResult> checks;
        private List<DiffEntry> diff;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProposeEditResult {
        private boolean ok;
        private List<GroupProposalOutcome> groups;
        private String errorCode;
        private String message;
    }
}
