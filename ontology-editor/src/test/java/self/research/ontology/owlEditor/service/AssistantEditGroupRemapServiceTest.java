package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantEditGroupRemapServiceTest {

    private final AssistantEditGroupRemapService remapService = new AssistantEditGroupRemapService();

    private EditEntry edit(long startLine, int lineCount, int lineDelta) {
        return EditEntry.builder().startLine(startLine).lineCount(lineCount)
                .originalText("").newText("").lineDelta(lineDelta).build();
    }

    private AssistantEditGroupDocument group(String id, AssistantEditGroupStatus status, EditEntry... edits) {
        return AssistantEditGroupDocument.builder()
                .id(id).projectId("proj-1").targetPath("turtle")
                .status(status).edits(List.of(edits)).build();
    }

    @Test
    void disjointNonAdjacentGroupsShiftIndependently() {
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(0, 1, 2));
        AssistantEditGroupDocument farSibling = group("g1", AssistantEditGroupStatus.PENDING, edit(10, 1, 0));
        AssistantEditGroupDocument nearSibling = group("g2", AssistantEditGroupStatus.PENDING, edit(1, 1, 0));

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(farSibling, nearSibling));

        assertEquals(2, touched.size());
        assertEquals(12, farSibling.getEdits().get(0).getStartLine());
        assertEquals(3, nearSibling.getEdits().get(0).getStartLine());
    }

    @Test
    void adjacentRangeShiftsNotMarkedStale() {
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(0, 2, -1));
        AssistantEditGroupDocument adjacentSibling = group("g1", AssistantEditGroupStatus.PENDING, edit(2, 1, 0));

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(adjacentSibling));

        assertEquals(1, touched.size());
        assertEquals(AssistantEditGroupStatus.PENDING, adjacentSibling.getStatus());
        assertEquals(1, adjacentSibling.getEdits().get(0).getStartLine());
    }

    @Test
    void overlappingRangeMarksWholeSiblingStaleWithoutPartialShift() {
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(2, 3, 0));
        EditEntry overlappingEdit = edit(3, 1, 0);
        EditEntry nonOverlappingEdit = edit(20, 1, 0);
        AssistantEditGroupDocument sibling = group("g1", AssistantEditGroupStatus.PENDING,
                overlappingEdit, nonOverlappingEdit);

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(sibling));

        assertEquals(1, touched.size());
        assertEquals(AssistantEditGroupStatus.STALE, sibling.getStatus());
        assertEquals(20, nonOverlappingEdit.getStartLine());
    }

    @Test
    void sequentialCommitsComposeTransitively() {
        AssistantEditGroupDocument sibling = group("g3", AssistantEditGroupStatus.PENDING, edit(10, 1, 0));

        AssistantEditGroupDocument commit1 = group("g1", AssistantEditGroupStatus.APPLIED, edit(0, 1, 2));
        remapService.remap(commit1, List.of(sibling));
        assertEquals(12, sibling.getEdits().get(0).getStartLine());

        AssistantEditGroupDocument commit2 = group("g2", AssistantEditGroupStatus.APPLIED, edit(1, 1, 3));
        remapService.remap(commit2, List.of(sibling));
        assertEquals(15, sibling.getEdits().get(0).getStartLine());
    }

    @Test
    void terminalStalePendingSiblingNeverRemappedAgain() {
        AssistantEditGroupDocument stale = group("g1", AssistantEditGroupStatus.STALE, edit(10, 1, 0));
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(0, 1, 5));

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(stale));

        assertTrue(touched.isEmpty());
        assertEquals(10, stale.getEdits().get(0).getStartLine());
    }

    @Test
    void conflictPendingSiblingNeverRemapped() {
        AssistantEditGroupDocument conflicted = group("g1", AssistantEditGroupStatus.CONFLICT, edit(10, 1, 0));
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(0, 1, 5));

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(conflicted));

        assertTrue(touched.isEmpty());
    }

    @Test
    void untouchedSiblingIsNotReturnedInTouchedList() {
        AssistantEditGroupDocument committed = group("g0", AssistantEditGroupStatus.APPLIED, edit(100, 1, 5));
        AssistantEditGroupDocument earlierSibling = group("g1", AssistantEditGroupStatus.PENDING, edit(0, 1, 0));

        List<AssistantEditGroupDocument> touched = remapService.remap(committed, List.of(earlierSibling));

        assertTrue(touched.isEmpty());
        assertEquals(0, earlierSibling.getEdits().get(0).getStartLine());
    }
}
