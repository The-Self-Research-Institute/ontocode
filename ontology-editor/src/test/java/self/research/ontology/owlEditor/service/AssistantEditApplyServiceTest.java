package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.EditEntry;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEditApplyServiceTest {

    @Mock
    private AssistantEditGroupRepository groupRepository;

    @Mock
    private StorageManager storageManager;

    @Mock
    private LineRangeSpliceWriter spliceWriter;

    @Mock
    private CodeViewReimportPipeline reimportPipeline;

    @Mock
    private AssistantEditGroupRemapService remapService;

    @Mock
    private AssistantEditSyntaxValidator syntaxValidator;

    @Mock
    private AssistantEditReferenceCoverageValidator referenceCoverageValidator;

    @Mock
    private SparqlDatasetService datasetService;

    private AssistantEditApplyService applyService;
    private Path splicedFile;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        ProjectWriteLockRegistry realLockRegistry = new ProjectWriteLockRegistry();
        applyService = new AssistantEditApplyService(groupRepository, storageManager, spliceWriter,
                reimportPipeline, remapService, realLockRegistry, syntaxValidator, referenceCoverageValidator, datasetService);
        splicedFile = Files.createTempFile("apply-test-spliced-", ".ttl");
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(splicedFile);
        when(remapService.remap(any(), any())).thenReturn(List.of());
        when(syntaxValidator.isValid(anyString(), anyString(), any())).thenReturn(true);
        when(referenceCoverageValidator.check(anyString(), anyString(), any()))
                .thenReturn(new AssistantEditReferenceCoverageValidator.CoverageResult(true, null));
    }

    private AssistantEditGroupDocument group(AssistantEditGroupStatus status, EditEntry... edits) {
        return AssistantEditGroupDocument.builder()
                .id("g1").sessionId("s1").projectId("proj-1").userEmail("u@x.com").targetPath("turtle")
                .status(status).edits(List.of(edits)).publicGraphVersionAtPropose(5L).build();
    }

    private EditEntry edit(long startLine, int lineCount, String originalText, String newText) {
        return EditEntry.builder().startLine(startLine).lineCount(lineCount)
                .originalText(originalText).newText(newText).lineDelta(0).build();
    }

    @Test
    void unknownGroupReturnsValidationFailed() {
        when(groupRepository.findById("g1")).thenReturn(Optional.empty());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
    }

    @Test
    void wrongUserReturnsValidationFailedNotAuthorizationLeak() {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group(AssistantEditGroupStatus.PENDING)));

        ApplyResult result = applyService.applyGroup("s1", "g1", "attacker@x.com");

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
    }

    @Test
    void appliedGroupReplaysIdempotentlyWithoutReapplying() throws Exception {
        AssistantEditGroupDocument applied = group(AssistantEditGroupStatus.APPLIED);
        applied.setAppliedRevision(42L);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(applied));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertTrue(result.isApplied());
        assertEquals(42L, result.getNewRevision());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void discardedGroupReturnsValidationFailed() {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group(AssistantEditGroupStatus.DISCARDED)));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
    }

    @Test
    void staleGroupReturnsStaleGroupErrorCode() {
        AssistantEditGroupDocument stale = group(AssistantEditGroupStatus.STALE);
        stale.setStaleReason("overlaps committed group g0");
        when(groupRepository.findById("g1")).thenReturn(Optional.of(stale));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("STALE_GROUP", result.getErrorCode());
        assertEquals("overlaps committed group g0", result.getMessage());
    }

    @Test
    void conflictGroupReturnsConflictErrorCode() {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group(AssistantEditGroupStatus.CONFLICT)));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
    }

    @Test
    void pendingWithMatchingVersionSkipsLiveReCheckAndApplies() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(10L, result.getNewRevision());
        verify(storageManager, never()).readCodeViewPage(anyString(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void pendingWithChangedVersionButMatchingContentStillApplies() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(99L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("old", 1, 1, 10, 100));
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 11L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(11L, result.getNewRevision());
    }

    @Test
    void pendingWithChangedVersionAndMismatchedContentMarksConflict() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(99L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("something else entirely", 1, 1, 10, 100));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());

        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.CONFLICT, captor.getValue().getStatus());
    }

    @Test
    void successfulApplyTriggersRemapAndReportsTouchedSiblings() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        AssistantEditGroupDocument touchedSibling = AssistantEditGroupDocument.builder()
                .id("sib-1").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();
        AssistantEditGroupDocument untouchedSibling = AssistantEditGroupDocument.builder()
                .id("sib-2").status(AssistantEditGroupStatus.PENDING).edits(List.of()).build();

        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 20L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus("proj-1", "turtle", AssistantEditGroupStatus.PENDING))
                .thenReturn(List.of(touchedSibling, untouchedSibling));
        when(remapService.remap(eq(pending), any())).thenReturn(List.of(touchedSibling));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(2, result.getRemappedPendingGroups().size());
        assertTrue(result.getRemappedPendingGroups().stream()
                .anyMatch(r -> r.serverGroupId().equals("sib-1") && r.remapped()));
        assertTrue(result.getRemappedPendingGroups().stream()
                .anyMatch(r -> r.serverGroupId().equals("sib-2") && !r.remapped()));
        verify(groupRepository).saveAll(List.of(touchedSibling));
    }

    @Test
    void pendingWithChangedVersionAndMatchingTextButBrokenSyntaxMarksConflict() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(99L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("old", 1, 1, 10, 100));
        when(syntaxValidator.isValid(eq("proj-1"), eq("turtle"), any())).thenReturn(false);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void liveMismatchSkipsRedundantSyntaxRecheck() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(99L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("something else entirely", 1, 1, 10, 100));

        applyService.applyGroup("s1", "g1", "u@x.com");

        verify(syntaxValidator, never()).isValid(anyString(), anyString(), any());
    }

    @Test
    void applyRequestsReimportWithSanitizationSkipped() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        applyService.applyGroup("s1", "g1", "u@x.com");

        ArgumentCaptor<CodeViewReimportPipeline.ReimportRequest> captor =
                ArgumentCaptor.forClass(CodeViewReimportPipeline.ReimportRequest.class);
        verify(reimportPipeline).reimport(captor.capture());
        assertTrue(captor.getValue().skipSanitization());
    }

    @Test
    void unexpectedReimportFailureReturnsRecoveryRequiredNotACrash() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB unreachable"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
    }

    @Test
    void reimportFailureWithGraphLeftEmptyRequiresRecoveryNotRetry() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("connection reset mid-stream"));
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of(), List.of(), false, null));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        assertTrue(result.getMessage().contains("haven't been verified"));
        assertTrue(result.getMessage().contains("won't be applied again"));
    }

    @Test
    void reimportFailureWithGraphStillPopulatedStillRequiresRecoveryNotRetry() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("connection reset mid-stream"));
        when(datasetService.execSelectCapped(eq("proj-1"), anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new SparqlDatasetService.CappedSparqlResult(List.of("s"), List.of(Map.of("s", "x")), false, null));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        assertTrue(result.getMessage().contains("haven't been verified"));
        assertTrue(result.getMessage().contains("won't be applied again"));
        assertFalse(result.getMessage().toLowerCase().contains("safe"));
    }

    @Test
    void reimportFailureMarksGroupRecoveryRequiredSoItCannotBeReappliedByAccident() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("connection reset mid-stream"));

        applyService.applyGroup("s1", "g1", "u@x.com");

        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.RECOVERY_REQUIRED, captor.getValue().getStatus());
    }

    @Test
    void recoveryRequiredGroupIsNeverReappliedOnRetry() throws Exception {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group(AssistantEditGroupStatus.RECOVERY_REQUIRED)));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
    }

    @Test
    void groupFromAnotherSessionIsRejectedLikeAnUnknownProposalAndNeverReimported() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);

        ApplyResult result = applyService.applyGroup("s-other", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        assertEquals("Unknown or unauthorized proposal", result.getMessage());
        verify(reimportPipeline, never()).reimport(any());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
    }

    @Test
    void missingSessionIdIsRejected() throws Exception {
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"))));

        ApplyResult result = applyService.applyGroup(null, "g1", "u@x.com");

        assertEquals("VALIDATION_FAILED", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
    }
}
