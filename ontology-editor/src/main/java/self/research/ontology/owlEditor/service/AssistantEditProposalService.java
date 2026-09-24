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
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AssistantEditProposalService {

    private final AssistantSessionService sessionService;
    private final AssistantEditGroupRepository groupRepository;
    private final StorageManager storageManager;
    private final AssistantEditSyntaxValidator syntaxValidator;

    @Value("${assistant.propose.max-edit-bytes:200000}")
    private int maxEditBytes;

    @Value("${assistant.propose.max-edits-per-group:20}")
    private int maxEditsPerGroup;

    @Value("${assistant.propose.max-groups-per-request:10}")
    private int maxGroupsPerRequest;

    @Value("${assistant.edit-group.ttl-hours:24}")
    private long ttlHours;

    public AssistantEditProposalService(AssistantSessionService sessionService,
                                         AssistantEditGroupRepository groupRepository,
                                         StorageManager storageManager,
                                         AssistantEditSyntaxValidator syntaxValidator) {
        this.sessionService = sessionService;
        this.groupRepository = groupRepository;
        this.storageManager = storageManager;
        this.syntaxValidator = syntaxValidator;
    }

    public ProposeEditResult propose(String sessionId, String userEmail, List<EditGroupInput> groups) {
        Optional<AssistantSessionDocument> sessionOpt = sessionService.getActiveSession(sessionId, userEmail);
        if (sessionOpt.isEmpty()) {
            return ProposeEditResult.builder().ok(false).errorCode("SESSION_NOT_FOUND")
                    .message("Session not found, expired, or not yours").build();
        }
        AssistantSessionDocument session = sessionOpt.get();

        if (!"local-edit".equals(session.getActionType())) {
            return ProposeEditResult.builder().ok(false).errorCode("VALIDATION_FAILED")
                    .message("This session's action type does not allow proposing edits").build();
        }

        if (groups.size() > maxGroupsPerRequest) {
            return ProposeEditResult.builder().ok(false).errorCode("VALIDATION_FAILED")
                    .message("Too many groups in one proposal (max " + maxGroupsPerRequest + ")").build();
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

    private GroupProposalOutcome proposeOneGroup(AssistantSessionDocument session, EditGroupInput groupInput,
                                                  long publicGraphVersion, Instant now, Instant expiresAt) {
        List<CheckResult> checks = new ArrayList<>();
        List<EditInput> sortedEdits = groupInput.edits().stream()
                .sorted(Comparator.comparingLong(e -> e.range() == null ? Long.MAX_VALUE : e.range().startLine()))
                .toList();
        String targetPath = sortedEdits.isEmpty() ? "" : sortedEdits.get(0).targetPath();

        boolean hasEdits = !sortedEdits.isEmpty();
        checks.add(new CheckResult("has_edits", hasEdits));

        boolean singleTargetPath = sortedEdits.stream().map(EditInput::targetPath).distinct().count() <= 1;
        checks.add(new CheckResult("single_target_path", singleTargetPath));

        boolean rangeWellFormed = sortedEdits.stream().allMatch(this::isRangeWellFormed);
        checks.add(new CheckResult("range_well_formed", rangeWellFormed));

        boolean noOverlap = sortedEdits.size() <= 1 || hasNoIntraGroupOverlap(sortedEdits);
        checks.add(new CheckResult("no_intra_group_overlap", noOverlap));

        boolean sizeOk = sortedEdits.size() <= maxEditsPerGroup
                && sortedEdits.stream().allMatch(e -> e.newText().length() <= maxEditBytes);
        checks.add(new CheckResult("size_limits", sizeOk));

        boolean liveMatch = singleTargetPath && rangeWellFormed
                && sortedEdits.stream().allMatch(e -> matchesLiveContent(session.getProjectId(), e));
        checks.add(new CheckResult("original_text_matches_live", liveMatch));

        boolean structurallySound = hasEdits && singleTargetPath && rangeWellFormed && noOverlap && sizeOk && liveMatch;
        boolean syntaxValid = !structurallySound
                || syntaxValidator.isValid(session.getProjectId(), targetPath, toSpliceEdits(sortedEdits));
        checks.add(new CheckResult("syntax_valid", syntaxValid));

        CheckResult referenceCoverage = !structurallySound
                ? new CheckResult("complete_reference_coverage", true)
                : checkCompleteReferenceCoverage(session.getProjectId(), targetPath, sortedEdits);
        checks.add(referenceCoverage);

        boolean passed = checks.stream().allMatch(CheckResult::passed);

        List<EditEntry> editEntries = sortedEdits.stream().map(this::toEditEntry).toList();
        List<DiffEntry> diff = sortedEdits.stream()
                .map(e -> new DiffEntry(e.targetPath(), e.originalText(), e.newText()))
                .toList();

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

        return GroupProposalOutcome.builder()
                .clientGroupId(groupInput.clientGroupId())
                .serverGroupId(document.getId())
                .validationPassed(passed)
                .checks(checks)
                .diff(diff)
                .build();
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

    private static final Pattern IDENTIFIER_TOKEN =
            Pattern.compile("<[^<>\\s]+>|(?:[A-Za-z_][\\w-]*)?:[A-Za-z_][\\w.-]*");

    private CheckResult checkCompleteReferenceCoverage(String projectId, String targetPath, List<EditInput> sortedEdits) {
        Set<String> removedTokens = new LinkedHashSet<>();
        Set<String> retainedTokens = new HashSet<>();
        for (EditInput edit : sortedEdits) {
            Set<String> oldTokens = extractTokens(edit.originalText());
            Set<String> newTokens = extractTokens(edit.newText());
            retainedTokens.addAll(newTokens);
            for (String token : oldTokens) {
                if (!newTokens.contains(token)) {
                    removedTokens.add(token);
                }
            }
        }
        removedTokens.removeAll(retainedTokens);
        if (removedTokens.isEmpty()) {
            return new CheckResult("complete_reference_coverage", true);
        }

        List<long[]> editedRanges = sortedEdits.stream()
                .filter(e -> e.range() != null)
                .map(e -> new long[]{e.range().startLine(), e.range().startLine() + e.range().lineCount()})
                .toList();

        String fullText;
        try {
            Path sourceFile = storageManager.ensureCodeViewFile(projectId, targetPath);
            fullText = Files.readString(sourceFile);
        } catch (Exception e) {
            log.warn("[Assistant] complete_reference_coverage scan skipped for project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            return new CheckResult("complete_reference_coverage", true);
        }

        String[] lines = fullText.split("\n", -1);
        List<String> missed = new ArrayList<>();
        for (String token : removedTokens) {
            for (int lineNo = 0; lineNo < lines.length; lineNo++) {
                if (withinAnyRange(lineNo, editedRanges)) {
                    continue;
                }
                if (lines[lineNo].contains(token)) {
                    missed.add(token + " still appears at line " + lineNo);
                    break;
                }
            }
        }

        if (missed.isEmpty()) {
            return new CheckResult("complete_reference_coverage", true);
        }
        return new CheckResult("complete_reference_coverage", false,
                "This proposal removes an identifier from the edited ranges, but it still appears elsewhere "
                        + "in the document, which this group would leave untouched: " + String.join("; ", missed)
                        + ". Include those occurrences in this group, or confirm they're intentionally kept.");
    }

    private Set<String> extractTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = IDENTIFIER_TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private boolean withinAnyRange(long lineNo, List<long[]> ranges) {
        for (long[] range : ranges) {
            if (lineNo >= range[0] && lineNo < range[1]) {
                return true;
            }
        }
        return false;
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
