package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;
import self.research.ontology.owlEditor.service.AssistantAuditService.AssistantAuditEvent;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryOutcome;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRecoveryServiceTest {

    @Mock
    private ProjectRecoveryLockService recoveryLockService;

    @Mock
    private AssistantApplyOperationService operationService;

    @Mock
    private CodeViewReimportPipeline reimportPipeline;

    @Mock
    private AssistantAuditService auditService;

    private ProjectWriteLockRegistry lockRegistry;
    private AssistantRecoveryService service;
    private AssistantApplyOperationDocument operation;
    private final Path snapshot = Path.of("snapshot-op-1.owl");
    private final Instant lockedAt = Instant.parse("2026-09-24T10:15:30Z");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lockRegistry = new ProjectWriteLockRegistry();
        service = new AssistantRecoveryService(recoveryLockService, operationService, reimportPipeline,
                lockRegistry, auditService);
        operation = AssistantApplyOperationDocument.builder().id("op-1").projectId("proj-1").groupId("g1")
                .status(ApplyOperationStatus.FAILED).snapshotPath(snapshot.toString()).build();
        when(operationService.findById("op-1")).thenReturn(Optional.of(operation));
        when(operationService.snapshotOf(operation)).thenReturn(snapshot);
        when(operationService.snapshotExists(operation)).thenReturn(true);
        when(operationService.findUnresolvedForProject("proj-1")).thenReturn(List.of(operation));
    }

    private ProjectRecoveryLockDocument lock(boolean canRestore) {
        return ProjectRecoveryLockDocument.builder().id("proj-1").locked(true).reason("apply failed")
                .lockedAt(lockedAt).operationId("op-1").canRestore(canRestore).build();
    }

    private AssistantAuditEvent lastAudit() {
        ArgumentCaptor<AssistantAuditEvent> captor = ArgumentCaptor.forClass(AssistantAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void stateOfUnlockedProjectReportsNotLocked() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.empty());

        RecoveryState state = service.state("proj-1");

        assertFalse(state.locked());
        assertFalse(state.canRestore());
        assertNull(state.reason());
    }

    @Test
    void stateOfLockedProjectReportsLockDetailsAndRestorability() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));

        RecoveryState state = service.state("proj-1");

        assertTrue(state.locked());
        assertEquals("apply failed", state.reason());
        assertEquals(lockedAt, state.lockedAt());
        assertEquals("op-1", state.operationId());
        assertTrue(state.canRestore());
    }

    @Test
    void stateCannotRestoreWhenTheSnapshotFileIsGone() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        when(operationService.snapshotExists(operation)).thenReturn(false);

        assertFalse(service.state("proj-1").canRestore());
    }

    @Test
    void stateCannotRestoreWhenTheLockSaysSo() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(false)));

        assertFalse(service.state("proj-1").canRestore());
    }

    @Test
    void restoreReimportsSnapshotMarksRolledBackAndReleasesLock() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        when(reimportPipeline.restoreSnapshot("proj-1", snapshot)).thenReturn(12L);
        when(operationService.findUnresolvedForProject("proj-1")).thenReturn(List.of());

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertTrue(outcome.ok());
        InOrder order = inOrder(reimportPipeline, operationService, recoveryLockService);
        order.verify(reimportPipeline).restoreSnapshot("proj-1", snapshot);
        order.verify(operationService).markRolledBack(operation, null, "USER_RESTORE");
        order.verify(recoveryLockService).release("proj-1", "u@x.com", "RESTORED");
        AssistantAuditEvent audit = lastAudit();
        assertEquals("recovery_restore", audit.operation());
        assertEquals("ok", audit.outcome());
        assertEquals("u@x.com", audit.actor());
        assertEquals("proj-1", audit.projectId());
        assertEquals(12L, audit.sourceRevision());
    }

    @Test
    void restoreAlsoClosesOtherUnresolvedOperationsOfTheProject() throws Exception {
        AssistantApplyOperationDocument older = AssistantApplyOperationDocument.builder().id("op-0")
                .projectId("proj-1").status(ApplyOperationStatus.FAILED).build();
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        when(operationService.findUnresolvedForProject("proj-1")).thenReturn(List.of(operation, older));

        assertTrue(service.restore("proj-1", "u@x.com").ok());

        verify(operationService).markClearedByUser(older);
        verify(operationService, never()).markClearedByUser(operation);
    }

    @Test
    void restoreWithoutSnapshotKeepsLockAndReturnsRecoveryRequired() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        when(operationService.snapshotExists(operation)).thenReturn(false);

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("RECOVERY_REQUIRED", outcome.errorCode());
        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
        verify(recoveryLockService, never()).release(anyString(), anyString(), anyString());
        verify(operationService, never()).markRolledBack(any(), any(), any());
        assertEquals("rejected", lastAudit().outcome());
    }

    @Test
    void restoreIsRefusedWhenTheLockIsMarkedNotRestorable() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(false)));

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("RECOVERY_REQUIRED", outcome.errorCode());
        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
        verify(recoveryLockService, never()).release(anyString(), anyString(), anyString());
    }

    @Test
    void restoreWithUnknownOperationKeepsLock() throws Exception {
        ProjectRecoveryLockDocument lock = lock(true);
        lock.setOperationId("op-missing");
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock));
        when(operationService.findById("op-missing")).thenReturn(Optional.empty());

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("RECOVERY_REQUIRED", outcome.errorCode());
        verify(recoveryLockService, never()).release(anyString(), anyString(), anyString());
    }

    @Test
    void failedRestoreKeepsLockAndSnapshot() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        when(reimportPipeline.restoreSnapshot("proj-1", snapshot)).thenThrow(new IOException("graphdb down"));

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("RECOVERY_REQUIRED", outcome.errorCode());
        assertTrue(outcome.message().contains("graphdb down"));
        verify(recoveryLockService, never()).release(anyString(), anyString(), anyString());
        verify(operationService, never()).markRolledBack(any(), any(), any());
        verify(operationService, never()).deleteSnapshot(any());
        AssistantAuditEvent audit = lastAudit();
        assertEquals("failed", audit.outcome());
        assertEquals("RECOVERY_REQUIRED", audit.errorCode());
    }

    @Test
    void restoreOfUnlockedProjectIsANoOpSuccess() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.empty());

        assertTrue(service.restore("proj-1", "u@x.com").ok());

        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
    }

    @Test
    void unexpectedErrorDuringRestoreReturnsRecoveryRequired() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenThrow(new IllegalStateException("mongo down"));

        RecoveryOutcome outcome = service.restore("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("RECOVERY_REQUIRED", outcome.errorCode());
    }

    @Test
    void restoreRunsUnderTheProjectWriteLock() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicBoolean restoredWhileHeld = new AtomicBoolean(false);
        AtomicBoolean held = new AtomicBoolean(false);
        when(reimportPipeline.restoreSnapshot("proj-1", snapshot)).thenAnswer(invocation -> {
            if (held.get()) {
                restoredWhileHeld.set(true);
            }
            return 1L;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> lockRegistry.runExclusive("proj-1", () -> {
                held.set(true);
                holding.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
                held.set(false);
                return null;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            Future<RecoveryOutcome> restore = executor.submit(() -> service.restore("proj-1", "u@x.com"));
            Thread.sleep(200);
            assertFalse(restore.isDone());
            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertTrue(restore.get(5, TimeUnit.SECONDS).ok());
            assertFalse(restoredWhileHeld.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clearMarksUnresolvedOperationsClearedAndReleasesLock() throws Exception {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));

        RecoveryOutcome outcome = service.clear("proj-1", "u@x.com");

        assertTrue(outcome.ok());
        InOrder order = inOrder(operationService, recoveryLockService);
        order.verify(operationService).markClearedByUser(operation);
        order.verify(recoveryLockService).release("proj-1", "u@x.com", "CLEARED");
        verify(reimportPipeline, never()).restoreSnapshot(anyString(), any());
        AssistantAuditEvent audit = lastAudit();
        assertEquals("recovery_clear", audit.operation());
        assertEquals("ok", audit.outcome());
    }

    @Test
    void clearFailureReturnsClearFailedAndIsAudited() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        doThrow(new IllegalStateException("mongo down")).when(recoveryLockService)
                .release(eq("proj-1"), anyString(), anyString());

        RecoveryOutcome outcome = service.clear("proj-1", "u@x.com");

        assertFalse(outcome.ok());
        assertEquals("CLEAR_FAILED", outcome.errorCode());
        assertEquals("failed", lastAudit().outcome());
    }

    @Test
    void auditFailureDoesNotBreakClear() {
        when(recoveryLockService.findActiveLock("proj-1")).thenReturn(Optional.of(lock(true)));
        doThrow(new IllegalStateException("audit down")).when(auditService).record(any());

        assertTrue(service.clear("proj-1", "u@x.com").ok());
    }
}
