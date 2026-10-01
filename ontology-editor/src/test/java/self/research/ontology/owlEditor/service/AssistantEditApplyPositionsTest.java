package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.AssistantAuditService.AssistantAuditEvent;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEditApplyPositionsTest extends AssistantEditApplyTestBase {

    @Test
    void remapResultFlagsSiblingsTheRemapMarkedStale() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        AssistantEditGroupDocument overlapping = AssistantEditGroupDocument.builder()
                .id("sib-stale").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();
        AssistantEditGroupDocument shifted = AssistantEditGroupDocument.builder()
                .id("sib-shifted").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();

        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 20L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus("proj-1", "turtle", AssistantEditGroupStatus.PENDING))
                .thenReturn(List.of(overlapping, shifted));
        when(remapService.remap(eq(pending), any())).thenAnswer(inv -> {
            overlapping.setStatus(AssistantEditGroupStatus.STALE);
            return List.of(overlapping, shifted);
        });

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertTrue(result.getRemappedPendingGroups().stream()
                .anyMatch(r -> r.serverGroupId().equals("sib-stale") && r.remapped() && r.stale()));
        assertTrue(result.getRemappedPendingGroups().stream()
                .anyMatch(r -> r.serverGroupId().equals("sib-shifted") && r.remapped() && !r.stale()));
    }

    @Test
    void appliedRangesShiftLaterEditsByEarlierLineDeltasAndSkipDeletions() {
        EditEntry grows = EditEntry.builder().startLine(2).lineCount(1).originalText("a").newText("a\nb\nc").lineDelta(2).build();
        EditEntry removed = EditEntry.builder().startLine(5).lineCount(2).originalText("x\ny").newText("").lineDelta(-2).build();
        EditEntry replaced = EditEntry.builder().startLine(10).lineCount(1).originalText("z").newText("z2").lineDelta(0).build();
        AssistantEditGroupDocument doc = AssistantEditGroupDocument.builder().targetPath("turtle")
                .edits(List.of(replaced, removed, grows)).build();

        List<AssistantEditGroupRemapService.AppliedRange> ranges = AssistantEditGroupRemapService.appliedRanges(doc);

        assertEquals(List.of(
                new AssistantEditGroupRemapService.AppliedRange("turtle", 2, 3),
                new AssistantEditGroupRemapService.AppliedRange("turtle", 10, 1)), ranges);
    }

    @Test
    void successfulApplyReportsTheLinesItWrote() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                EditEntry.builder().startLine(4).lineCount(1).originalText("old").newText("new\nmore").lineDelta(1).build());
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 4, 1))
                .thenReturn(new StorageManager.CodeViewPage("old", 4, 1, 10, 100));
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 6L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any())).thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals(List.of(new AssistantEditGroupRemapService.AppliedRange("turtle", 4, 2)), result.getAppliedRanges());
    }

    @Test
    void insertionOnlyGroupRemappedAtTheCurrentVersionIsRevalidatedAndApplied() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(3, 0, "", "ex:new a owl:Class ."));
        pending.setPositionsVerifiedAtVersion(6L);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(6L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 7L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(syntaxValidator).isValid(eq("proj-1"), eq("turtle"), any());
        verify(referenceCoverageValidator).check(eq("proj-1"), eq("turtle"), any());
    }

    @Test
    void insertionOnlyGroupRemappedAtAnOlderVersionIsStillAConflict() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(3, 0, "", "ex:new a owl:Class ."));
        pending.setPositionsVerifiedAtVersion(6L);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(7L);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void cleanCommitStampsPendingSiblingsWithTheNewVersion() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        AssistantEditGroupDocument sibling = AssistantEditGroupDocument.builder()
                .id("sib-1").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 9L, true));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus("proj-1", "turtle", AssistantEditGroupStatus.PENDING))
                .thenReturn(List.of(sibling));
        when(remapService.remap(eq(pending), any())).thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(9L, sibling.getPositionsVerifiedAtVersion());
        verify(groupRepository).saveAll(List.of(sibling));
        assertFalse(result.getRemappedPendingGroups().get(0).remapped());
    }

    @Test
    void reserializedCommitDoesNotStampSiblings() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        AssistantEditGroupDocument sibling = AssistantEditGroupDocument.builder()
                .id("sib-1").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 9L, false));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus("proj-1", "turtle", AssistantEditGroupStatus.PENDING))
                .thenReturn(List.of(sibling));
        when(remapService.remap(eq(pending), any())).thenReturn(List.of());

        applyService.applyGroup("s1", "g1", "u@x.com");

        assertNull(sibling.getPositionsVerifiedAtVersion());
    }
}
