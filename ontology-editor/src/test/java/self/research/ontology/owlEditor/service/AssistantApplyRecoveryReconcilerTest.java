package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument.ApplyOperationStatus;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument.AssistantEditGroupStatus;
import self.research.ontology.owlEditor.repository.AssistantEditGroupRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantApplyRecoveryReconcilerTest {

    private AssistantApplyOperationService operationService;
    private ProjectRecoveryLockService lockService;
    private AssistantEditGroupRepository groupRepository;
    private AssistantApplyRecoveryReconciler reconciler;

    @BeforeEach
    void setUp() {
        operationService = mock(AssistantApplyOperationService.class);
        lockService = mock(ProjectRecoveryLockService.class);
        groupRepository = mock(AssistantEditGroupRepository.class);
        reconciler = new AssistantApplyRecoveryReconciler(operationService, lockService, groupRepository,
                new ProjectWriteLockRegistry());
        when(operationService.findInterrupted()).thenReturn(List.of());
        when(operationService.findAllUnresolved()).thenReturn(List.of());
    }

    private AssistantApplyOperationDocument operation(String id, String projectId, ApplyOperationStatus status) {
        return AssistantApplyOperationDocument.builder().id(id).projectId(projectId).groupId("group-" + id)
                .status(status).snapshotPath("/snapshots/" + id + ".owl").build();
    }

    @Test
    void operationsStuckMidApplyFreezeTheirProjectsAsRestorable() {
        AssistantApplyOperationDocument prepared = operation("op-1", "proj-a", ApplyOperationStatus.PREPARED);
        AssistantApplyOperationDocument importing = operation("op-2", "proj-b", ApplyOperationStatus.GRAPH_IMPORTING);
        AssistantEditGroupDocument group = AssistantEditGroupDocument.builder().id("group-op-2")
                .status(AssistantEditGroupStatus.PENDING).build();
        when(operationService.findInterrupted()).thenReturn(List.of(prepared, importing));
        when(operationService.snapshotExists(any())).thenReturn(true);
        when(groupRepository.findById("group-op-2")).thenReturn(Optional.of(group));
        when(groupRepository.findById("group-op-1")).thenReturn(Optional.empty());

        AssistantApplyRecoveryReconciler.ReconcileReport report = reconciler.reconcile();

        assertEquals(2, report.interruptedOperations());
        assertEquals(2, report.lockedProjects());
        verify(lockService).lock(eq("proj-a"), anyString(), eq("op-1"), eq(true));
        verify(lockService).lock(eq("proj-b"), anyString(), eq("op-2"), eq(true));
        verify(operationService).markFailedAwaitingRecovery(eq(prepared), anyString());
        verify(operationService).markFailedAwaitingRecovery(eq(importing), anyString());
        assertEquals(AssistantEditGroupStatus.RECOVERY_REQUIRED, group.getStatus());
        verify(groupRepository).save(group);
    }

    @Test
    void interruptedOperationWithoutASnapshotIsLockedAsNotRestorable() {
        AssistantApplyOperationDocument importing = operation("op-3", "proj-c", ApplyOperationStatus.GRAPH_IMPORTING);
        when(operationService.findInterrupted()).thenReturn(List.of(importing));
        when(operationService.snapshotExists(importing)).thenReturn(false);
        when(groupRepository.findById(anyString())).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(lockService).lock(eq("proj-c"), anyString(), eq("op-3"), eq(false));
    }

    @Test
    void nothingInterruptedLocksNothing() {
        AssistantApplyRecoveryReconciler.ReconcileReport report = reconciler.reconcile();

        assertEquals(0, report.lockedProjects());
        verify(lockService, never()).lock(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void sweepKeepsSnapshotsOfEveryUnresolvedOperation() {
        AssistantApplyOperationDocument awaiting = operation("op-9", "proj-z", ApplyOperationStatus.FAILED);
        when(operationService.findAllUnresolved()).thenReturn(List.of(awaiting));
        when(operationService.sweepOrphanSnapshots(any())).thenReturn(3);

        AssistantApplyRecoveryReconciler.ReconcileReport report = reconciler.reconcile();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> keep = ArgumentCaptor.forClass(Set.class);
        verify(operationService).sweepOrphanSnapshots(keep.capture());
        assertEquals(Set.of("op-9"), keep.getValue());
        assertEquals(3, report.removedSnapshots());
        verify(operationService).pruneFinishedOlderThan(AssistantApplyRecoveryReconciler.FINISHED_OPERATION_RETENTION);
    }

    @Test
    void oneFailingProjectDoesNotStopTheOthersFromBeingFrozen() {
        AssistantApplyOperationDocument broken = operation("op-1", "proj-a", ApplyOperationStatus.PREPARED);
        AssistantApplyOperationDocument fine = operation("op-2", "proj-b", ApplyOperationStatus.PREPARED);
        when(operationService.findInterrupted()).thenReturn(List.of(broken, fine));
        when(groupRepository.findById(anyString())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("mongo blip")).when(operationService)
                .markFailedAwaitingRecovery(eq(broken), anyString());

        AssistantApplyRecoveryReconciler.ReconcileReport report = reconciler.reconcile();

        assertEquals(1, report.lockedProjects());
        verify(lockService).lock(eq("proj-b"), anyString(), eq("op-2"), anyBoolean());
    }

    @Test
    void startupHookSwallowsFailuresSoTheAppStillStarts() {
        when(operationService.findInterrupted()).thenThrow(new IllegalStateException("mongo down"));

        reconciler.reconcileOnStartup();
    }
}
