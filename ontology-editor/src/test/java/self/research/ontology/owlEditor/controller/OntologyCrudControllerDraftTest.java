package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService;
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
import static org.mockito.Mockito.when;

class OntologyCrudControllerDraftTest {

    @Test
    @SuppressWarnings("unchecked")
    void editingBeforeThePrivateDraftExistsAnswersConflictNotServerError() {
        ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any()))
                .thenThrow(new DraftNotReadyException());
        OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
                mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
                new ProjectWriteLockRegistry(), recorder);

        ResponseEntity<?> response = controller.mutate("proj--file", new OntologyCrudController.MutationRequest(
                List.of(new OntologyMutationService.MutationOp("createClass", "http://x/A", "A", null, null, null, null,
                        null, null, null, null, null, null, null, null)), "u1", "User", "s1"), true, null);

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("draftCopyNotReady"));
        assertTrue(String.valueOf(body.get("error")).contains("not ready"));
    }
}
