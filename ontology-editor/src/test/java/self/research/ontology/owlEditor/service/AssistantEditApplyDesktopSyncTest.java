package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantEditApplyDesktopSyncTest extends AssistantEditApplyTestBase {

    @Mock
    ProjectImportService projectImportService;

    @BeforeEach
    void wireDesktopSync() {
        ReflectionTestUtils.setField(applyService, "projectImportService", projectImportService);
    }

    @Test
    void unsyncedDesktopEditsReachTheTripleStoreBeforeTheAssistantEditIsApplied() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        when(projectImportService.isFusekiSyncPending("proj-1")).thenReturn(true);
        when(projectImportService.syncProjectToFuseki("proj-1")).thenReturn(Map.of("synced", true));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        InOrder order = inOrder(projectImportService, spliceWriter, reimportPipeline);
        order.verify(projectImportService).syncProjectToFuseki("proj-1");
        order.verify(spliceWriter).splice(any(), anyString(), any());
        order.verify(reimportPipeline).reimport(any());
    }

    @Test
    void aFailedDesktopSyncStopsTheApplyBeforeTheGraphIsTouched() throws Exception {
        pendingReadyToApply();
        when(projectImportService.isFusekiSyncPending("proj-1")).thenReturn(true);
        when(projectImportService.syncProjectToFuseki("proj-1"))
                .thenReturn(Map.of("synced", false, "error", "is the triple store running?"));

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertFalse(result.isOk());
        assertEquals("APPLY_FAILED", result.getErrorCode());
        assertTrue(result.getMessage().contains("is the triple store running?"));
        verify(spliceWriter, never()).splice(any(), anyString(), any());
        verify(reimportPipeline, never()).reimport(any());
    }

    @Test
    void nothingIsSyncedWhenNoDesktopEditsAreWaiting() throws Exception {
        pendingReadyToApply();
        when(reimportPipeline.reimport(any())).thenReturn(new ReimportResult("turtle", RDFFormat.TURTLE, 10L));
        when(projectImportService.isFusekiSyncPending("proj-1")).thenReturn(false);

        ApplyResult result = applyService.applyGroup("s1", "g1", "u@x.com");

        assertTrue(result.isOk());
        verify(projectImportService, never()).syncProjectToFuseki(anyString());
    }

    @Test
    void draftScopedGroupsDoNotTouchTheDesktopWorkingCopy() {
        AssistantEditGroupDocument draftGroup = pendingReadyToApply();
        draftGroup.setDraft(true);
        draftGroup.setDraftUserId("u@x.com");

        applyService.applyGroup("s1", "g1", "u@x.com");

        verify(projectImportService, never()).isFusekiSyncPending(anyString());
        verify(projectImportService, never()).syncProjectToFuseki(anyString());
    }
}
