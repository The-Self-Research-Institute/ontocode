package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.MutationPreconditionGuard;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyVersionStamps;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyCrudControllerPreconditionTest {

    private final ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
    private final OntologyVersionStamps stamps = mock(OntologyVersionStamps.class);
    private final OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
            mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
            new ProjectWriteLockRegistry(), recorder);

    OntologyCrudControllerPreconditionTest() {
        ReflectionTestUtils.setField(controller, "preconditionGuard", new MutationPreconditionGuard(stamps));
        when(stamps.publicRevision("proj")).thenReturn(10L);
        when(stamps.draftVersion("proj", "u1")).thenReturn("5.2");
        when(stamps.rulesFingerprint("proj")).thenReturn("rules-1");
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn("cs-1");
    }

    private OntologyCrudController.MutationRequest request(PreviewStamp precondition) {
        return new OntologyCrudController.MutationRequest(
                List.of(new OntologyMutationService.MutationOp("addClassAssertion", "http://x/cara", null, null, null, null, null,
                        null, null, null, null, null, null, null, null)), "u1", "User", "s1", precondition);
    }

    private HttpServletRequest http() {
        return mock(HttpServletRequest.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStalePublicPreviewIsRefusedAndNothingIsWritten() {
        ResponseEntity<?> response = controller.mutate("proj", request(new PreviewStamp(9L, null, "rules-1")), false, http());

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("stale"));
        assertTrue(String.valueOf(body.get("reasons")).contains("the ontology changed"));
        verify(recorder, never()).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStaleDraftPreviewIsRefusedAndNothingIsWritten() {
        ResponseEntity<?> response = controller.mutate("proj", request(new PreviewStamp(null, "5.1", null)), true, http());

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("stale"));
        assertEquals(true, body.get("draft"));
        verify(recorder, never()).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    void aMatchingPreviewIsWritten() {
        ResponseEntity<?> response = controller.mutate("proj", request(new PreviewStamp(10L, null, "rules-1")), false, http());

        assertEquals(200, response.getStatusCode().value());
        verify(recorder).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    void aRequestWithoutAPreconditionBehavesAsBefore() {
        ResponseEntity<?> response = controller.mutate("proj", request(null), false, http());

        assertEquals(200, response.getStatusCode().value());
    }
}
