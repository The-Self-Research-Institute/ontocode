package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditRange;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.DiffEntry;

import java.util.ArrayList;
import java.util.List;

@Slf4j
final class ProposedEdits {

    private ProposedEdits() {
    }

    static List<DiffEntry> toDiffEntries(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new DiffEntry(e.targetPath(), e.originalText(), e.newText(),
                        e.range() == null ? null : e.range().startLine(),
                        e.range() == null ? null : e.range().lineCount()))
                .toList();
    }

    static boolean matchesLiveContentInOnePass(StorageManager storageManager, String projectId, String targetPath,
                                               List<EditInput> sortedEdits, StorageManager.ContentScope scope) {
        return liveMatchDetailInOnePass(storageManager, projectId, targetPath, sortedEdits, scope).matches();
    }

    static CodeViewRangeMatcher.MatchResult liveMatchDetailInOnePass(StorageManager storageManager, String projectId,
                                                                     String targetPath, List<EditInput> sortedEdits,
                                                                     StorageManager.ContentScope scope) {
        List<CodeViewRangeMatcher.ExpectedRange> expected = sortedEdits.stream()
                .map(e -> new CodeViewRangeMatcher.ExpectedRange(e.range().startLine(), e.range().lineCount(),
                        e.originalText()))
                .toList();
        return new CodeViewRangeMatcher(storageManager).matchWithDetail(projectId, targetPath, expected, scope);
    }

    static boolean isRangeWellFormed(EditInput edit) {
        if (edit.range() == null || edit.range().startLine() < 0 || edit.range().lineCount() < 0) {
            return false;
        }
        if (edit.range().lineCount() == 0) {
            return edit.originalText() == null || edit.originalText().isEmpty();
        }
        return true;
    }

    record SiblingSnapResult(List<EditInput> edits, String detail) {}

    static SiblingSnapResult snapInsertsPastSiblingEdits(List<EditInput> sortedEdits) {
        List<EditInput> result = new ArrayList<>(sortedEdits);
        List<String> moves = new ArrayList<>();
        for (int i = 0; i < result.size() - 1; i++) {
            EditInput a = result.get(i);
            EditInput b = result.get(i + 1);
            if (a.range() == null || b.range() == null || b.range().lineCount() != 0) {
                continue;
            }
            long thisEnd = a.range().startLine() + a.range().lineCount();
            if (thisEnd > b.range().startLine()) {
                moves.add("line " + (b.range().startLine() + 1) + " to line " + (thisEnd + 1));
                result.set(i + 1, new EditInput(b.targetPath(), new EditRange(thisEnd, 0), b.originalText(), b.newText()));
            }
        }
        if (moves.isEmpty()) {
            return new SiblingSnapResult(sortedEdits, null);
        }
        String detail = moves.size() == 1
                ? "Moved the insertion at " + moves.get(0)
                        + " so it lands after the edit right before it instead of inside it."
                : "Moved " + moves.size() + " insertions (" + String.join(", ", moves)
                        + ") so they land after the edit right before them instead of inside it.";
        return new SiblingSnapResult(result, detail);
    }

    record OverlapCheck(boolean ok, String detail) {}

    static OverlapCheck checkNoIntraGroupOverlap(List<EditInput> sortedEdits) {
        for (int i = 0; i < sortedEdits.size() - 1; i++) {
            EditInput a = sortedEdits.get(i);
            EditInput b = sortedEdits.get(i + 1);
            if (a.range() == null || b.range() == null) {
                return new OverlapCheck(false, "One of the edits in this group is missing a line range.");
            }
            long thisEnd = a.range().startLine() + a.range().lineCount();
            long nextStart = b.range().startLine();
            if (thisEnd > nextStart) {
                String first = a.range().lineCount() == 0
                        ? "the insertion at line " + a.range().startLine()
                        : "the edit covering lines " + a.range().startLine() + "-" + (thisEnd - 1);
                String second = b.range().lineCount() == 0
                        ? "the insertion at line " + nextStart
                        : "the edit starting at line " + nextStart;
                return new OverlapCheck(false, "In this group, " + second + " overlaps " + first
                        + ". Move it to start at line " + thisEnd + " or later, or combine the two into one edit.");
            }
        }
        return new OverlapCheck(true, null);
    }

    static OverlapCheck checkNoCrossGroupOverlap(List<EditEntry> sortedEntries) {
        for (int i = 0; i < sortedEntries.size() - 1; i++) {
            EditEntry a = sortedEntries.get(i);
            EditEntry b = sortedEntries.get(i + 1);
            long thisEnd = a.getStartLine() + a.getLineCount();
            long nextStart = b.getStartLine();
            if (thisEnd > nextStart) {
                String first = a.getLineCount() == 0
                        ? "the insertion at line " + a.getStartLine()
                        : "the edit covering lines " + a.getStartLine() + "-" + (thisEnd - 1);
                String second = b.getLineCount() == 0
                        ? "the insertion at line " + nextStart
                        : "the edit starting at line " + nextStart;
                return new OverlapCheck(false, "Across this batch, " + second + " overlaps " + first + ".");
            }
        }
        return new OverlapCheck(true, null);
    }

    static boolean matchesLiveContent(StorageManager storageManager, String projectId, EditInput edit,
                                      StorageManager.ContentScope scope) {
        return liveMatchDetail(storageManager, projectId, edit, scope).matches();
    }

    static CodeViewRangeMatcher.MatchResult liveMatchDetail(StorageManager storageManager, String projectId,
                                                            EditInput edit, StorageManager.ContentScope scope) {
        if (edit.range().lineCount() == 0) {
            return CodeViewRangeMatcher.MatchResult.OK;
        }
        try {
            StorageManager.CodeViewPage page = scope.draft()
                    ? storageManager.resolveCodeViewPage(projectId, edit.targetPath(), edit.range().startLine(),
                            edit.range().lineCount(), scope)
                    : storageManager.readCodeViewPage(
                            projectId, edit.targetPath(), edit.range().startLine(), edit.range().lineCount());
            if (page.content().equals(edit.originalText())) {
                return CodeViewRangeMatcher.MatchResult.OK;
            }
            return CodeViewRangeMatcher.MatchResult.mismatch("The document changed at line "
                    + (edit.range().startLine() + 1) + " since this edit was proposed.");
        } catch (Exception e) {
            log.warn("[Assistant] Live-content check failed for {}:{}-{}: {}",
                    edit.targetPath(), edit.range().startLine(), edit.range().lineCount(), e.getMessage());
            return CodeViewRangeMatcher.MatchResult.mismatch(
                    "Could not read the document to check it (" + e.getMessage() + ").");
        }
    }

    static EditEntry toEditEntry(EditInput edit) {
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

    static List<LineRangeSpliceWriter.SpliceEdit> toSpliceEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new LineRangeSpliceWriter.SpliceEdit(
                        e.range() != null ? e.range().startLine() : 0,
                        e.range() != null ? e.range().lineCount() : 0,
                        e.newText()))
                .toList();
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    static List<AssistantEditReferenceCoverageValidator.CoverageEdit> toCoverageEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new AssistantEditReferenceCoverageValidator.CoverageEdit(
                        e.range() != null ? e.range().startLine() : 0,
                        e.range() != null ? e.range().lineCount() : 0,
                        e.originalText(),
                        e.newText()))
                .toList();
    }

    static List<AssistantEditSemanticValidator.SemanticEdit> toSemanticEdits(List<EditInput> sortedEdits) {
        return sortedEdits.stream()
                .map(e -> new AssistantEditSemanticValidator.SemanticEdit(
                        e.range().startLine(), e.range().lineCount(), e.originalText(), e.newText()))
                .toList();
    }
}
