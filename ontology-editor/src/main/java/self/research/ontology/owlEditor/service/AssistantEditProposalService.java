package self.research.ontology.owlEditor.service;

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

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AssistantEditProposalService {

    public static final String PROPOSE_OPERATION = "edit_propose";
    public static final String RENAME_CHECK = "rename_occurrences_complete";

    private static final int MAX_AUDIT_DETAIL_CHARS = 500;

    private final AssistantSessionService sessionService;
    private final AssistantEditGroupRepository groupRepository;
    private final StorageManager storageManager;
    private final AssistantEditSyntaxValidator syntaxValidator;
    private final AssistantEditReferenceCoverageValidator referenceCoverageValidator;
    private final AssistantRenameService renameService;
    private final AssistantEditSemanticValidator semanticValidator;
    private final AssistantAuditService auditService;

    @Value("${assistant.propose.max-edit-bytes:200000}")
    private int maxEditBytes;

    @Value("${assistant.propose.max-edits-per-group:20}")
    private int maxEditsPerGroup;

    @Value("${assistant.propose.max-rename-lines:5000}")
    private int maxRenameLines;

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
                                         AssistantEditSemanticValidator semanticValidator,
                                         AssistantAuditService auditService) {
        this.sessionService = sessionService;
        this.groupRepository = groupRepository;
        this.storageManager = storageManager;
        this.syntaxValidator = syntaxValidator;
        this.referenceCoverageValidator = referenceCoverageValidator;
        this.renameService = renameService;
        this.semanticValidator = semanticValidator;
        this.auditService = auditService;
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

        long publicGraphVersion = storageManager.getPublicGraphVersion(session.getProjectId());
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlHours * 3600);

        List<GroupProposalOutcome> outcomes = new ArrayList<>();
        for (EditGroupInput groupInput : groups) {
            outcomes.add(proposeOneGroup(session, groupInput, publicGraphVersion, now, expiresAt));
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
        List<CheckResult> checks = new ArrayList<>();
        List<EditInput> inputEdits = groupInput.edits() == null ? List.of() : groupInput.edits();
        EditOperation operation = groupInput.operation();
        boolean derived = operation != null;
        Set<String> introducedByOperation = Set.of();
        String renameSummary = null;
        if (derived) {
            String operationPath = operation.targetPath() == null ? "" : operation.targetPath();
            if (!inputEdits.isEmpty()) {
                return rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                        new CheckResult(RENAME_CHECK, false,
                                "A group can carry either explicit edits or an operation, not both."));
            }
            AssistantRenameService.RenameDerivation derivation =
                    renameService.derive(session.getProjectId(), operation, maxRenameLines);
            if (!derivation.ok()) {
                return rejectGroup(session, groupInput, operationPath, publicGraphVersion, now, expiresAt,
                        new CheckResult(RENAME_CHECK, false, derivation.detail()));
            }
            checks.add(new CheckResult(RENAME_CHECK, true, derivation.detail()));
            introducedByOperation = Set.of(derivation.replacementIri());
            renameSummary = AssistantRenameService.RENAME_IDENTIFIER + " <" + derivation.targetIri() + "> -> <"
                    + derivation.replacementIri() + ">, " + derivation.occurrences() + " occurrences";
            inputEdits = derivation.edits().stream()
                    .map(e -> new EditInput(operation.targetPath(), new EditRange(e.line(), 1), e.originalText(),
                            e.newText()))
                    .toList();
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

        boolean hasEdits = !sortedEdits.isEmpty();
        checks.add(new CheckResult("has_edits", hasEdits));

        boolean singleTargetPath = !targetPath.isBlank()
                && sortedEdits.stream().map(EditInput::targetPath).distinct().count() <= 1;
        checks.add(new CheckResult("single_target_path", singleTargetPath || !hasEdits));

        boolean rangeWellFormed = sortedEdits.stream().allMatch(this::isRangeWellFormed);
        checks.add(new CheckResult("range_well_formed", rangeWellFormed));

        boolean noOverlap = sortedEdits.size() <= 1 || hasNoIntraGroupOverlap(sortedEdits);
        checks.add(new CheckResult("no_intra_group_overlap", noOverlap));

        int maxEdits = derived ? maxRenameLines : maxEditsPerGroup;
        boolean sizeOk = sortedEdits.size() <= maxEdits
                && sortedEdits.stream().allMatch(e -> e.newText() != null && e.newText().length() <= maxEditBytes);
        checks.add(new CheckResult("size_limits", sizeOk));

        boolean liveMatch = singleTargetPath && rangeWellFormed
                && (derived ? noOverlap && matchesLiveContentInOnePass(session.getProjectId(), targetPath, sortedEdits)
                : sortedEdits.stream().allMatch(e -> matchesLiveContent(session.getProjectId(), e)));
        checks.add(new CheckResult("original_text_matches_live", liveMatch));

        boolean structurallySound = hasEdits && singleTargetPath && rangeWellFormed && noOverlap && sizeOk && liveMatch;
        CheckResult syntax;
        if (structurallySound) {
            AssistantEditSyntaxValidator.SyntaxResult result =
                    syntaxValidator.check(session.getProjectId(), targetPath, toSpliceEdits(sortedEdits));
            syntax = new CheckResult("syntax_valid", result.valid(), result.detail());
        } else {
            syntax = new CheckResult("syntax_valid", true);
        }
        checks.add(syntax);

        CheckResult referenceCoverage = !structurallySound
                ? new CheckResult("complete_reference_coverage", true)
                : toCheckResult(referenceCoverageValidator.check(
                        session.getProjectId(), targetPath, toCoverageEdits(sortedEdits)));
        checks.add(referenceCoverage);

        if (!structurallySound) {
            checks.addAll(AssistantEditSemanticValidator.skipped(
                    "Skipped because the group failed its structural checks."));
        } else if (!syntax.passed()) {
            checks.addAll(AssistantEditSemanticValidator.skipped(
                    "Skipped because the edited document does not parse."));
        } else {
            checks.addAll(semanticValidator.check(session.getProjectId(), targetPath,
                    toSemanticEdits(sortedEdits), introducedByOperation));
        }

        boolean passed = checks.stream().allMatch(CheckResult::passed);

        List<EditEntry> editEntries = sortedEdits.stream().map(this::toEditEntry).toList();
        List<DiffEntry> diff = sortedEdits.stream()
                .map(e -> new DiffEntry(e.targetPath(), e.originalText(), e.newText()))
                .toList();

        String summary = renameSummary != null ? renameSummary
                : sortedEdits.size() + " edit" + (sortedEdits.size() == 1 ? "" : "s") + " on " + targetPath;
        return persistGroup(session, groupInput, targetPath, editEntries, passed, publicGraphVersion, now, expiresAt,
                checks, diff, summary);
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

    private boolean matchesLiveContentInOnePass(String projectId, String targetPath, List<EditInput> sortedEdits) {
        try {
            Path file = storageManager.ensureCodeViewFile(projectId, targetPath);
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                long lineNo = 0;
                String line = reader.readLine();
                for (EditInput edit : sortedEdits) {
                    long start = edit.range().startLine();
                    int count = edit.range().lineCount();
                    if (count == 0) {
                        continue;
                    }
                    while (line != null && lineNo < start) {
                        line = reader.readLine();
                        lineNo++;
                    }
                    StringBuilder live = new StringBuilder();
                    for (int k = 0; k < count; k++) {
                        if (line == null) {
                            return false;
                        }
                        if (k > 0) {
                            live.append('\n');
                        }
                        live.append(line);
                        line = reader.readLine();
                        lineNo++;
                    }
                    if (!live.toString().equals(edit.originalText())) {
                        return false;
                    }
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("[Assistant] Live-content check failed for {}: {}", targetPath, e.getMessage());
            return false;
        }
    }

    private boolean isRangeWellFormed(EditInput edit) {
        if (edit.range() == null || edit.range().startLine() < 0 || edit.range().lineCount() < 0) {
            return false;
        }
        if (edit.range().lineCount() == 0) {
            return edit.originalText() == null || edit.originalText().isEmpty();
        }
        return true;
    }

    private boolean hasNoIntraGroupOverlap(List<EditInput> sortedEdits) {
        for (int i = 0; i < sortedEdits.size() - 1; i++) {
            if (sortedEdits.get(i).range() == null || sortedEdits.get(i + 1).range() == null) {
                return false;
            }
            long thisEnd = sortedEdits.get(i).range().startLine() + sortedEdits.get(i).range().lineCount();
            long nextStart = sortedEdits.get(i + 1).range().startLine();
            if (thisEnd > nextStart) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesLiveContent(String projectId, EditInput edit) {
        if (edit.range().lineCount() == 0) {
            return true;
        }
        try {
            StorageManager.CodeViewPage page = storageManager.readCodeViewPage(
                    projectId, edit.targetPath(), edit.range().startLine(), edit.range().lineCount());
            return page.content().equals(edit.originalText());
        } catch (Exception e) {
            log.warn("[Assistant] Live-content check failed for {}:{}-{}: {}",
                    edit.targetPath(), edit.range().startLine(), edit.range().lineCount(), e.getMessage());
            return false;
        }
    }

    private EditEntry toEditEntry(EditInput edit) {
        long startLine = edit.range() != null ? edit.range().startLine() : 0;
        int lineCount = edit.range() != null ? edit.range().lineCount() : 0;
        int newTextLines = countLines(edit.newText());
        int delta = newTextLines - lineCount;
        return EditEntry.builder()
                .startLine(startLine)
                .lineCount(lineCount)
                .originalText(edit.originalText())
                .newText(edit.newText())
                .lineDelta(delta)
                .build();
    }

    private List<LineRangeSpliceWriter.SpliceEdit> toSpliceEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new LineRangeSpliceWriter.SpliceEdit(
                        e.range() != null ? e.range().startLine() : 0,
                        e.range() != null ? e.range().lineCount() : 0,
                        e.newText()))
                .toList();
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    private List<AssistantEditReferenceCoverageValidator.CoverageEdit> toCoverageEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new AssistantEditReferenceCoverageValidator.CoverageEdit(
                        e.range() != null ? e.range().startLine() : 0,
                        e.range() != null ? e.range().lineCount() : 0,
                        e.originalText(),
                        e.newText()))
                .toList();
    }

    private List<AssistantEditSemanticValidator.SemanticEdit> toSemanticEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new AssistantEditSemanticValidator.SemanticEdit(
                        e.range().startLine(), e.range().lineCount(), e.originalText(), e.newText()))
                .toList();
    }

    private CheckResult toCheckResult(AssistantEditReferenceCoverageValidator.CoverageResult result) {
        return new CheckResult("complete_reference_coverage", result.covered(), result.detail());
    }

    public record CheckResult(String name, boolean passed, String detail) {
        public CheckResult(String name, boolean passed) {
            this(name, passed, null);
        }
    }

    public record DiffEntry(String targetPath, String before, String after) {}

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
