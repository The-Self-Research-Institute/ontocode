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

class AssistantEditApplyRecoveryTest extends AssistantEditApplyTestBase {

    @Test
    void successfulApplyMovesTheOperationThroughImportingToCommittedAndDiffsAgainstTheSnapshot() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(operationService, spliceWriter, reimportPipeline);
        order.verify(spliceWriter).splice(any(), anyString(), any());
        order.verify(operationService).prepare(any(), eq("u@x.com"));
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
        verify(operationService, never()).markImporting(any());
        verify(reimportPipeline, never()).reimport(any());
        verify(groupRepository, never()).save(any());
        assertEquals(AssistantEditGroupStatus.PENDING, pending.getStatus());
    }

    @Test
    void spliceFailureStopsBeforeAnyOperationOrImport() throws Exception {
        pendingReadyToApply();
        when(spliceWriter.splice(any(), anyString(), any())).thenThrow(new java.io.IOException("cannot write temp file"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertEquals("APPLY_FAILED", result.getErrorCode());
        verify(operationService, never()).prepare(any(), anyString());
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
                referenceCoverageValidator, datasetService, operationService, recoveryLockService,
                new PageBackedRangeMatcher(storageManager));
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));

        ApplyResult result = unaudited.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(auditService, never()).record(any());
    }
}
