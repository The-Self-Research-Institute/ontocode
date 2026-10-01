package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditInput;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.DiffEntry;

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
                                               List<EditInput> sortedEdits) {
        List<CodeViewRangeMatcher.ExpectedRange> expected = sortedEdits.stream()
                .map(e -> new CodeViewRangeMatcher.ExpectedRange(e.range().startLine(), e.range().lineCount(),
                        e.originalText()))
                .toList();
        return new CodeViewRangeMatcher(storageManager).allMatch(projectId, targetPath, expected);
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

    static boolean hasNoIntraGroupOverlap(List<EditInput> sortedEdits) {
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

    static boolean matchesLiveContent(StorageManager storageManager, String projectId, EditInput edit) {
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
