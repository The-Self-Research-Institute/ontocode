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

    @Mock
    private AssistantApplyOperationService operationService;

    @Mock
    private ProjectRecoveryLockService recoveryLockService;

    @Mock
    private AssistantAuditService auditService;

    @Mock
    private AssistantSessionRepository sessionRepository;

    private AssistantEditApplyService applyService;
    private Path splicedFile;
    private Path snapshotFile;
    private AssistantApplyOperationDocument operation;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        ProjectWriteLockRegistry realLockRegistry = new ProjectWriteLockRegistry();
        applyService = new AssistantEditApplyService(groupRepository, storageManager, spliceWriter,
                reimportPipeline, remapService, realLockRegistry, syntaxValidator, referenceCoverageValidator, datasetService,
                operationService, recoveryLockService, auditService, sessionRepository);
        when(sessionRepository.findById(anyString())).thenReturn(Optional.empty());
        splicedFile = Files.createTempFile("apply-test-spliced-", ".ttl");
        snapshotFile = Files.createTempFile("apply-test-snapshot-", ".owl");
        operation = AssistantApplyOperationDocument.builder().id("op-1").projectId("proj-1").groupId("g1")
                .status(ApplyOperationStatus.PREPARED).snapshotPath(snapshotFile.toString()).build();
        when(operationService.prepare(any(), anyString())).thenReturn(operation);
        when(operationService.snapshotOf(operation)).thenReturn(snapshotFile);
        when(operationService.snapshotExists(operation)).thenReturn(true);
        when(operationService.findUnresolvedForGroup(anyString())).thenReturn(Optional.empty());
        when(recoveryLockService.isLocked(anyString())).thenReturn(false);
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
        when(spliceWriter.splice(any(), anyString(), any())).thenReturn(splicedFile);
        when(remapService.remap(any(), any())).thenReturn(List.of());
        when(syntaxValidator.isValid(anyString(), anyString(), any())).thenReturn(true);
        when(referenceCoverageValidator.check(anyString(), anyString(), any()))
                .thenReturn(new AssistantEditReferenceCoverageValidator.CoverageResult(true, null));
        when(storageManager.readCodeViewPage(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(new StorageManager.CodeViewPage("old", 1, 1, 10, 100));
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
    void pendingWithMatchingVersionStillChecksLiveTextButSkipsSyntaxRecheckAndApplies() throws Exception {
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
        verify(storageManager).readCodeViewPage("proj-1", "turtle", 1, 1);
        verify(syntaxValidator, never()).isValid(anyString(), anyString(), any());
        verify(referenceCoverageValidator, never()).check(anyString(), anyString(), any());
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
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));

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
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));
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
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));
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
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));

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

    @Test
    void matchingVersionWithChangedLiveTextIsAConflictSoARestartCannotHideAnEdit() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 1, 1))
                .thenReturn(new StorageManager.CodeViewPage("edited elsewhere", 1, 1, 10, 100));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
        ArgumentCaptor<AssistantEditGroupDocument> captor = ArgumentCaptor.forClass(AssistantEditGroupDocument.class);
        verify(groupRepository).save(captor.capture());
        assertEquals(AssistantEditGroupStatus.CONFLICT, captor.getValue().getStatus());
    }

    @Test
    void everyReplacedRangeIsCheckedNotJustTheFirst() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"), edit(20, 2, "a\nb", "c"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(storageManager.readCodeViewPage("proj-1", "turtle", 20, 2))
                .thenReturn(new StorageManager.CodeViewPage("a\nchanged", 20, 2, 10, 100));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void insertionWithUnchangedVersionIsAppliedWithoutAComparison() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(3, 0, "", "ex:new a owl:Class ."));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 12L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(storageManager, never()).readCodeViewPage(anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    void insertionOnlyGroupWithChangedVersionIsAConflictBecauseItsPositionCannotBeVerified() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(3, 0, "", "ex:new a owl:Class ."));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(6L);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
        assertTrue(result.getMessage().contains("inserted lines"));
        verify(reimportPipeline, never()).reimport(any());
        verify(syntaxValidator, never()).isValid(anyString(), anyString(), any());
    }

    @Test
    void mixedGroupWithAnInsertionAndChangedVersionIsAConflict() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING,
                edit(1, 1, "old", "new"), edit(8, 0, "", "ex:x a owl:Class ."));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(6L);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("CONFLICT", result.getErrorCode());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void changedVersionWithMatchingTextButLostReferenceCoverageIsAConflict() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(6L);
        when(referenceCoverageValidator.check(eq("proj-1"), eq("turtle"), any()))
                .thenReturn(new AssistantEditReferenceCoverageValidator.CoverageResult(false, "ex:old still used"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("CONFLICT", result.getErrorCode());
        verify(syntaxValidator).isValid(eq("proj-1"), eq("turtle"), any());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void missingProposeVersionIsTreatedAsChanged() throws Exception {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        pending.setPublicGraphVersionAtPropose(null);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(0L);
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 3L));
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(syntaxValidator).isValid(eq("proj-1"), eq("turtle"), any());
    }

    private AssistantEditGroupDocument pendingReadyToApply() {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());
        return pending;
    }

    @Test
    void successfulApplyMovesTheOperationThroughImportingToCommittedAndDiffsAgainstTheSnapshot() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(operationService, spliceWriter, reimportPipeline);
        order.verify(operationService).prepare(any(), eq("u@x.com"));
        order.verify(spliceWriter).splice(any(), anyString(), any());
        order.verify(operationService).markImporting(operation);
        order.verify(reimportPipeline).reimport(any());
        order.verify(operationService).markCommitted(operation);
        ArgumentCaptor<CodeViewReimportPipeline.ReimportRequest> captor =
                ArgumentCaptor.forClass(CodeViewReimportPipeline.ReimportRequest.class);
        verify(reimportPipeline).reimport(captor.capture());
        assertEquals(snapshotFile, captor.getValue().oldContentFileForDiff());
        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
        verify(recoveryLockService, never()).lock(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void reimportFailureWithSuccessfulRollbackMarksConflictAndDoesNotFreezeTheProject() throws Exception {
        AssistantEditGroupDocument pending = pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB timed out"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("CONFLICT", result.getErrorCode());
        assertTrue(result.getMessage().contains("rolled back"));
        assertTrue(result.getMessage().contains("GraphDB timed out"));
        verify(reimportPipeline).restoreSnapshot("proj-1", snapshotFile);
        verify(operationService).markRolledBack(eq(operation), eq("GraphDB timed out"), anyString());
        verify(operationService, never()).markFailedAwaitingRecovery(any(), anyString());
        verify(recoveryLockService, never()).lock(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(AssistantEditGroupStatus.CONFLICT, pending.getStatus());
        verify(groupRepository).save(pending);
    }

    @Test
    void conflictAfterRollbackIsReportedAgainOnRetryWithoutReimporting() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB timed out"));
        applyService.applyGroup("s1", "g1", "u@x.com");

        ApplyResult retry = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("CONFLICT", retry.getErrorCode());
        assertTrue(retry.getMessage().contains("rolled back"));
        verify(reimportPipeline, org.mockito.Mockito.times(1)).reimport(any());
    }

    @Test
    void reimportFailureWithFailedRollbackMarksRecoveryRequiredAndFreezesTheProject() throws Exception {
        AssistantEditGroupDocument pending = pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB timed out"));
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        assertTrue(result.getMessage().contains("locked"));
        verify(operationService).markFailedAwaitingRecovery(eq(operation), org.mockito.ArgumentMatchers.contains("still down"));
        verify(operationService, never()).markRolledBack(any(), anyString(), anyString());
        verify(operationService, never()).deleteSnapshot(any());
        verify(recoveryLockService).lock(eq("proj-1"), anyString(), eq("op-1"), eq(true));
        assertEquals(AssistantEditGroupStatus.RECOVERY_REQUIRED, pending.getStatus());
    }

    @Test
    void reimportFailureWithoutASnapshotFreezesTheProjectAsNotRestorable() throws Exception {
        pendingReadyToApply();
        when(operationService.snapshotExists(operation)).thenReturn(false);
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB timed out"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
        verify(recoveryLockService).lock(eq("proj-1"), anyString(), eq("op-1"), eq(false));
    }

    @Test
    void snapshotCaptureFailureRefusesToApplyAndLeavesTheGroupPending() throws Exception {
        AssistantEditGroupDocument pending = pendingReadyToApply();
        when(operationService.prepare(any(), anyString())).thenThrow(new java.io.IOException("disk full"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("APPLY_FAILED", result.getErrorCode());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
        verify(reimportPipeline, never()).reimport(any());
        verify(groupRepository, never()).save(any());
        assertEquals(AssistantEditGroupStatus.PENDING, pending.getStatus());
    }

    @Test
    void spliceFailureAbandonsTheOperationBeforeAnyImport() throws Exception {
        pendingReadyToApply();
        when(spliceWriter.splice(any(), anyString(), any())).thenThrow(new java.io.IOException("cannot write temp file"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("APPLY_FAILED", result.getErrorCode());
        verify(operationService).markAbandoned(operation, "cannot write temp file");
        verify(operationService, never()).markImporting(any());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void lockedProjectRefusesToApply() throws Exception {
        pendingReadyToApply();
        when(recoveryLockService.isLocked("proj-1")).thenReturn(true);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("PROJECT_RECOVERY_LOCKED", result.getErrorCode());
        verify(operationService, never()).prepare(any(), anyString());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void appliedGroupStillReplaysWhileTheProjectIsLocked() throws Exception {
        AssistantEditGroupDocument applied = group(AssistantEditGroupStatus.APPLIED);
        applied.setAppliedRevision(7L);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(applied));
        when(recoveryLockService.isLocked("proj-1")).thenReturn(true);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(7L, result.getNewRevision());
    }

    @Test
    void retryWhileTheLastOperationIsUnresolvedNeverStartsASecondImport() throws Exception {
        pendingReadyToApply();
        AssistantApplyOperationDocument stuck = AssistantApplyOperationDocument.builder().id("op-0").groupId("g1")
                .status(ApplyOperationStatus.GRAPH_IMPORTING).build();
        when(operationService.findUnresolvedForGroup("g1")).thenReturn(Optional.of(stuck));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("RECOVERY_REQUIRED", result.getErrorCode());
        verify(operationService, never()).prepare(any(), anyString());
        verify(spliceWriter, never()).splice(any(), anyString(), any());
        verify(reimportPipeline, never()).reimport(any());
    }

    private AssistantAuditEvent auditedEvent() {
        ArgumentCaptor<AssistantAuditEvent> captor = ArgumentCaptor.forClass(AssistantAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void successfulApplyIsAuditedWithTheSessionsProviderAndModel() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(AssistantSessionDocument.builder()
                .id("s1").userEmail("u@x.com").projectId("proj-1").provider("anthropic").model("claude-x").build()));

        applyService.applyGroup("s1", "g1", "u@x.com");

        AssistantAuditEvent event = auditedEvent();
        assertEquals("apply", event.operation());
        assertEquals("ok", event.outcome());
        assertEquals("u@x.com", event.actor());
        assertEquals("proj-1", event.projectId());
        assertEquals("s1", event.sessionId());
        assertEquals("g1", event.groupId());
        assertEquals(10L, event.sourceRevision());
        assertEquals("anthropic", event.provider());
        assertEquals("claude-x", event.model());
        assertNull(event.errorCode());
    }

    @Test
    void failedApplyIsAuditedWithItsErrorCode() throws Exception {
        pendingReadyToApply();
        when(recoveryLockService.isLocked("proj-1")).thenReturn(true);

        applyService.applyGroup("s1", "g1", "u@x.com");

        AssistantAuditEvent event = auditedEvent();
        assertEquals("failed", event.outcome());
        assertEquals("PROJECT_RECOVERY_LOCKED", event.errorCode());
        assertEquals("proj-1", event.projectId());
        assertNull(event.provider());
        assertNull(event.model());
    }

    @Test
    void recoveryRequiredAfterAFailedRollbackIsAudited() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenThrow(new java.io.IOException("GraphDB timed out"));
        when(reimportPipeline.restoreSnapshot(anyString(), any())).thenThrow(new java.io.IOException("still down"));

        applyService.applyGroup("s1", "g1", "u@x.com");

        AssistantAuditEvent event = auditedEvent();
        assertEquals("failed", event.outcome());
        assertEquals("RECOVERY_REQUIRED", event.errorCode());
    }

    @Test
    void rejectedUnknownProposalIsAuditedWithoutAProjectOrAnotherUsersSessionDetails() {
        when(groupRepository.findById("g1")).thenReturn(Optional.empty());
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(AssistantSessionDocument.builder()
                .id("s1").userEmail("owner@x.com").provider("openai").model("gpt").build()));

        applyService.applyGroup("s1", "g1", "attacker@x.com");

        AssistantAuditEvent event = auditedEvent();
        assertEquals("failed", event.outcome());
        assertEquals("VALIDATION_FAILED", event.errorCode());
        assertNull(event.projectId());
        assertNull(event.provider());
        assertNull(event.model());
    }

    @Test
    void auditFailureDoesNotChangeTheApplyResult() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        doThrow(new IllegalStateException("audit down")).when(auditService).record(any());

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        assertEquals(10L, result.getNewRevision());
    }

    @Test
    void serviceBuiltWithoutAuditingStillApplies() throws Exception {
        AssistantEditApplyService unaudited = new AssistantEditApplyService(groupRepository, storageManager, spliceWriter,
                reimportPipeline, remapService, new ProjectWriteLockRegistry(), syntaxValidator,
                referenceCoverageValidator, datasetService, operationService, recoveryLockService);
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));

        ApplyResult result = unaudited.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(auditService, never()).record(any());
    }
}
