package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.service.DatatypeDefinitionService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatatypeDefinitionControllerTest {

    @Mock
    private DatatypeDefinitionService definitionService;
    @Mock
    private CollaborativeEditService collaborativeEditService;
    @Mock
    private OntologyHistoryService historyService;

    private DatatypeDefinitionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new DatatypeDefinitionController(definitionService, collaborativeEditService, historyService);
    }

    private static DatatypeDefinitionEntity entity(String datatypeIri) {
        DatatypeDefinitionEntity e = new DatatypeDefinitionEntity();
        e.setDatatypeIri(datatypeIri);
        return e;
    }

    @Test
    void createDefinitionRecordsHistory() {
        var req = new DatatypeDefinitionController.CreateDatatypeDefinitionRequest();
        req.datatypeIri = "http://ex.org/Age";
        req.expression = "xsd:integer[>= 0, <= 150]";
        when(definitionService.createDefinition(eq("proj-1"), eq("http://ex.org/Age"), anyString(), anyString()))
                .thenReturn(entity("http://ex.org/Age"));

        controller.createDefinition("proj-1", req, "u1", "User One");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MutationOp>> opsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).recordGroupedMutations(
                eq("proj-1"), eq("u1"), eq("User One"), opsCaptor.capture(), eq(false));
        assertEquals("addDatatypeDefinition", opsCaptor.getValue().get(0).type());
    }

    @Test
    void updateDefinitionRecordsHistory() {
        when(definitionService.updateDefinition(eq("proj-1"), eq("def-1"), any(), any()))
                .thenReturn(Optional.of(entity("http://ex.org/Age")));
        var req = new DatatypeDefinitionController.UpdateDatatypeDefinitionRequest();
        req.expression = "xsd:integer[>= 0, <= 120]";

        controller.updateDefinition("proj-1", "def-1", req, "u1", "User One");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MutationOp>> opsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).recordGroupedMutations(
                eq("proj-1"), eq("u1"), eq("User One"), opsCaptor.capture(), eq(false));
        assertEquals("updateDatatypeDefinition", opsCaptor.getValue().get(0).type());
    }

    @Test
    void deleteDefinitionRecordsHistory() {
        when(definitionService.findById("proj-1", "def-1")).thenReturn(Optional.of(entity("http://ex.org/Age")));
        when(definitionService.deleteDefinition("proj-1", "def-1")).thenReturn(true);

        controller.deleteDefinition("proj-1", "def-1", "u1", "User One");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MutationOp>> opsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).recordGroupedMutations(
                eq("proj-1"), eq("u1"), eq("User One"), opsCaptor.capture(), eq(false));
        assertEquals("deleteDatatypeDefinition", opsCaptor.getValue().get(0).type());
    }
}
