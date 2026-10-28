package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.ChangeOrigin;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OntologyCrudControllerOriginTest {

    private final ExactChangeRecorder recorder = mock(ExactChangeRecorder.class);
    private final OntologyCrudController controller = new OntologyCrudController(mock(OntologyMutationService.class),
            mock(DraftTrackingService.class), mock(CollaborativeEditService.class), mock(ProjectMetadataService.class),
            new ProjectWriteLockRegistry(), recorder);

    private static OntologyCrudController.MutationRequest request(OntologyCrudController.MutationOrigin origin) {
        return new OntologyCrudController.MutationRequest(
                List.of(new OntologyMutationService.MutationOp("createClass", "http://x/A", "A", null, null, null, null,
                        null, null, null, null, null, null, null, null)), "u1", "Sam", "s1", null, origin);
    }

    private ChangeOrigin recordedOrigin(OntologyCrudController.MutationOrigin origin, boolean draft) {
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("cs");
        controller.mutate("proj", request(origin), draft, new MockHttpServletRequest());
        ArgumentCaptor<ChangeOrigin> captured = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(recorder).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any(), captured.capture());
        return captured.getValue();
    }

    @Test
    void aMutationWithAnOriginIsRecordedWithThatOrigin() {
        String label = "Added from rule results: Age under 18, Critical ward ICU";
        ChangeOrigin origin = recordedOrigin(new OntologyCrudController.MutationOrigin("swrl-rules", label), false);
        assertEquals(ChangeOrigin.TOOL, origin.source());
        assertEquals("swrl-rules", origin.toolSource());
        assertEquals(label, origin.toolLabel());
    }

    @Test
    void aDraftMutationWithAnOriginIsRecordedWithThatOrigin() {
        ChangeOrigin origin = recordedOrigin(new OntologyCrudController.MutationOrigin("swrl-rules", "Rules"), true);
        assertEquals("swrl-rules", origin.toolSource());
        assertEquals("Rules", origin.toolLabel());
    }

    @Test
    void aMutationWithoutAnOriginUsesTheOriginalRecordingCall() {
        when(recorder.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn("cs");
        controller.mutate("proj", request(null), false, new MockHttpServletRequest());
        verify(recorder).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any());
        verify(recorder, never()).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void anInvalidSourceFallsBackToManual() {
        for (String bad : new String[]{"Swrl Rules", "", "UPPER", "a".repeat(41), "x/y", null}) {
            ExactChangeRecorder fresh = mock(ExactChangeRecorder.class);
            when(fresh.recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn("cs");
            OntologyCrudController c = new OntologyCrudController(mock(OntologyMutationService.class),
                    mock(DraftTrackingService.class), mock(CollaborativeEditService.class),
                    mock(ProjectMetadataService.class), new ProjectWriteLockRegistry(), fresh);
            c.mutate("proj", request(new OntologyCrudController.MutationOrigin(bad, "label")), false,
                    new MockHttpServletRequest());
            ArgumentCaptor<ChangeOrigin> captured = ArgumentCaptor.forClass(ChangeOrigin.class);
            verify(fresh).recordGrouped(anyString(), anyBoolean(), anyString(), anyString(), any(), any(), captured.capture());
            assertEquals(ChangeOrigin.MANUAL, captured.getValue().source(), "source " + bad);
            assertNull(captured.getValue().toolSource());
            assertNull(captured.getValue().toolLabel());
        }
    }

    @Test
    void theLabelIsTrimmedCappedAndStrippedOfControlCharacters() {
        ChangeOrigin origin = ChangeOrigin.fromTool("swrl-rules", "  Added\u0000 from\r\n rules\u0007  ");
        assertEquals("Added from rules", origin.toolLabel());
        assertEquals(300, ChangeOrigin.fromTool("swrl-rules", "x".repeat(500)).toolLabel().length());
        assertNull(ChangeOrigin.fromTool("swrl-rules", "   ").toolLabel());
        assertNull(ChangeOrigin.fromTool("swrl-rules", null).toolLabel());
    }

    @Test
    void aToolOriginKeepsItsOwnChangeSetIdForUndo() {
        ChangeOrigin first = ChangeOrigin.fromTool("swrl-rules", "a");
        ChangeOrigin second = ChangeOrigin.fromTool("swrl-rules", "a");
        assertNotNull(first.changeSetId());
        assertEquals(false, first.changeSetId().equals(second.changeSetId()));
    }
}
