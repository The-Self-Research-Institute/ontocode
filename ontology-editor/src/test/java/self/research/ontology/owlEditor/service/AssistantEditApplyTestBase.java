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

abstract class AssistantEditApplyTestBase {

    @Mock
    AssistantEditGroupRepository groupRepository;

    @Mock
    StorageManager storageManager;

    @Mock
    LineRangeSpliceWriter spliceWriter;

    @Mock
    CodeViewReimportPipeline reimportPipeline;

    @Mock
    AssistantEditGroupRemapService remapService;

    @Mock
    AssistantEditSyntaxValidator syntaxValidator;

    @Mock
    AssistantEditReferenceCoverageValidator referenceCoverageValidator;

    @Mock
    SparqlDatasetService datasetService;

    @Mock
    AssistantApplyOperationService operationService;

    @Mock
    ProjectRecoveryLockService recoveryLockService;

    @Mock
    AssistantAuditService auditService;

    @Mock
    AssistantSessionRepository sessionRepository;

    AssistantEditApplyService applyService;
    Path splicedFile;
    Path snapshotFile;
    AssistantApplyOperationDocument operation;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        ProjectWriteLockRegistry realLockRegistry = new ProjectWriteLockRegistry();
        applyService = new AssistantEditApplyService(groupRepository, storageManager, spliceWriter,
                reimportPipeline, remapService, realLockRegistry, syntaxValidator, referenceCoverageValidator, datasetService,
                operationService, recoveryLockService, auditService, sessionRepository,
                new PageBackedRangeMatcher(storageManager));
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
        when(remapService.stampVerifiedPositions(any(), any(), anyLong())).thenAnswer(inv ->
                new AssistantEditGroupRemapService().stampVerifiedPositions(
                        inv.getArgument(0), inv.getArgument(1), inv.<Long>getArgument(2)));
        when(syntaxValidator.isValid(anyString(), anyString(), any())).thenReturn(true);
        when(referenceCoverageValidator.check(anyString(), anyString(), any()))
                .thenReturn(new AssistantEditReferenceCoverageValidator.CoverageResult(true, null));
        when(storageManager.readCodeViewPage(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(new StorageManager.CodeViewPage("old", 1, 1, 10, 100));
    }

    AssistantEditGroupDocument group(AssistantEditGroupStatus status, EditEntry... edits) {
        return AssistantEditGroupDocument.builder()
                .id("g1").sessionId("s1").projectId("proj-1").userEmail("u@x.com").targetPath("turtle")
                .status(status).edits(List.of(edits)).publicGraphVersionAtPropose(5L).build();
    }

    EditEntry edit(long startLine, int lineCount, String originalText, String newText) {
        return EditEntry.builder().startLine(startLine).lineCount(lineCount)
                .originalText(originalText).newText(newText).lineDelta(0).build();
    }

    AssistantEditGroupDocument pendingReadyToApply() {
        AssistantEditGroupDocument pending = group(AssistantEditGroupStatus.PENDING, edit(1, 1, "old", "new"));
        when(groupRepository.findById("g1")).thenReturn(Optional.of(pending));
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(5L);
        when(groupRepository.findByProjectIdAndTargetPathAndStatus(anyString(), anyString(), any()))
                .thenReturn(List.of());
        return pending;
    }

    AssistantAuditEvent auditedEvent() {
        ArgumentCaptor<AssistantAuditEvent> captor = ArgumentCaptor.forClass(AssistantAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }
}
