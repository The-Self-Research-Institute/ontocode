package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.service.DatatypeDefinitionDraftService;
import self.research.ontology.owlEditor.service.DatatypeDefinitionDraftService.Change;
import self.research.ontology.owlEditor.service.DatatypeDefinitionService;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentSnapshots;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatatypeDefinitionControllerTest {

    @Mock
    private DatatypeDefinitionService definitionService;
    @Mock
    private DatatypeDefinitionDraftService drafts;
    @Mock
    private DraftTrackingService draftTracking;
    @Mock
    private CollaborativeEditService collaborativeEditService;
    @Mock
    private ExactChangeRecorder exactRecorder;
    @Mock
    private DocumentHistory documentHistory;
    @Mock
    private DocumentSnapshots snapshots;

    private DatatypeDefinitionController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new DatatypeDefinitionController(definitionService, drafts, draftTracking, collaborativeEditService, exactRecorder,
                documentHistory, snapshots, new ProjectWriteLockRegistry());
        when(exactRecorder.recordAround(anyString(), anyBoolean(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return ((Supplier<String>) invocation.getArgument(5)).get();
        });
        when(snapshots.snapshot(any())).thenAnswer(invocation -> {
            DatatypeDefinitionEntity entity = invocation.getArgument(0);
            return entity == null ? null : "{\"expression\":\"" + entity.getExpression() + "\"}";
        });
    }

    private static DatatypeDefinitionEntity entity(String datatypeIri, String expression) {
        DatatypeDefinitionEntity e = new DatatypeDefinitionEntity();
        e.setDatatypeIri(datatypeIri);
        e.setExpression(expression);
        return e;
    }

    @SuppressWarnings("unchecked")
    private List<MutationOp> capturedOps() {
        ArgumentCaptor<List<MutationOp>> ops = ArgumentCaptor.forClass(List.class);
        verify(exactRecorder).recordAround(eq("proj-1"), eq(false), eq("u1"), ops.capture(), any(), any());
        return ops.getValue();
    }

    @Test
    void createDefinitionCapturesTheGraphChangeAndKeepsTheNewDocument() {
        var req = new DatatypeDefinitionController.CreateDatatypeDefinitionRequest();
        req.datatypeIri = "http://ex.org/Age";
        req.expression = "xsd:integer[>= 0, <= 150]";
        when(definitionService.createDefinition(eq("proj-1"), eq("http://ex.org/Age"), anyString(), anyString()))
                .thenReturn(entity("http://ex.org/Age", "xsd:integer[>= 0, <= 150]"));

        controller.createDefinition("proj-1", req, false, "u1", "User One", null);

        assertEquals("addDatatypeDefinition", capturedOps().get(0).type());
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.DATATYPE_DEFINITION),
                isNull(), eq("{\"expression\":\"xsd:integer[>= 0, <= 150]\"}"), eq("addDatatypeDefinition"),
                eq("http://ex.org/Age"), eq("http://ex.org/Age"), isNull(), eq("xsd:integer[>= 0, <= 150]"), anyString());
    }

    @Test
    void updateDefinitionKeepsTheDocumentBeforeAndAfter() {
        DatatypeDefinitionEntity before = entity("http://ex.org/Age", "xsd:integer[>= 0, <= 150]");
        when(definitionService.findById("proj-1", "def-1")).thenReturn(Optional.of(before));
        when(definitionService.updateDefinition(eq("proj-1"), eq("def-1"), any(), any()))
                .thenReturn(Optional.of(entity("http://ex.org/Age", "xsd:integer[>= 0, <= 120]")));
        var req = new DatatypeDefinitionController.UpdateDatatypeDefinitionRequest();
        req.expression = "xsd:integer[>= 0, <= 120]";

        controller.updateDefinition("proj-1", "def-1", req, false, "u1", "User One", null);

        List<MutationOp> ops = capturedOps();
        assertEquals("deleteDatatypeDefinition", ops.get(0).type());
        assertEquals("addDatatypeDefinition", ops.get(1).type());
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.DATATYPE_DEFINITION),
                eq("{\"expression\":\"xsd:integer[>= 0, <= 150]\"}"), eq("{\"expression\":\"xsd:integer[>= 0, <= 120]\"}"),
                eq("updateDatatypeDefinition"), eq("http://ex.org/Age"), eq("http://ex.org/Age"),
                eq("xsd:integer[>= 0, <= 150]"), eq("xsd:integer[>= 0, <= 120]"), anyString());
    }

    @Test
    void updateOfAMissingDefinitionIsNotFoundAndRecordsNothing() {
        when(definitionService.findById("proj-1", "def-9")).thenReturn(Optional.empty());

        var response = controller.updateDefinition("proj-1", "def-9",
                new DatatypeDefinitionController.UpdateDatatypeDefinitionRequest(), false, "u1", "User One", null);

        assertEquals(404, response.getStatusCode().value());
        verify(documentHistory, never()).record(anyString(), any(), any(), any(), any(), anyString(), anyString(), any(),
                any(), any(), anyString());
    }

    @Test
    void deleteDefinitionKeepsTheDeletedDocument() {
        when(definitionService.findById("proj-1", "def-1")).thenReturn(Optional.of(entity("http://ex.org/Age", "x")));
        when(definitionService.deleteDefinition("proj-1", "def-1")).thenReturn(true);

        controller.deleteDefinition("proj-1", "def-1", false, "u1", "User One", null);

        assertEquals("deleteDatatypeDefinition", capturedOps().get(0).type());
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.DATATYPE_DEFINITION),
                eq("{\"expression\":\"x\"}"), isNull(), eq("deleteDatatypeDefinition"), eq("http://ex.org/Age"),
                eq("http://ex.org/Age"), eq("x"), isNull(), anyString());
    }

    @SuppressWarnings("unchecked")
    private List<MutationOp> capturedDraftOps() {
        ArgumentCaptor<List<MutationOp>> ops = ArgumentCaptor.forClass(List.class);
        verify(exactRecorder).recordAround(eq("proj-1"), eq(true), eq("u1"), ops.capture(), any(), any());
        return ops.getValue();
    }

    @Test
    void aDraftCreateGoesToTheDraftAndIsNotSharedWithCollaborators() {
        var req = new DatatypeDefinitionController.CreateDatatypeDefinitionRequest();
        req.datatypeIri = "http://ex.org/Age";
        req.expression = "xsd:integer[>= 0]";
        DatatypeDefinitionEntity created = entity("http://ex.org/Age", "xsd:integer[>= 0]");
        when(drafts.create(eq("proj-1"), eq("u1"), eq("http://ex.org/Age"), anyString(), anyString()))
                .thenReturn(new Change(null, created));

        var response = controller.createDefinition("proj-1", req, true, "u1", "User One", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("addDatatypeDefinition", capturedDraftOps().get(0).type());
        verify(draftTracking).recordDrafts(eq("proj-1"), eq("u1"), eq("User One"), any(), anyString());
        verify(definitionService, never()).createDefinition(anyString(), anyString(), anyString(), anyString());
        verify(collaborativeEditService, never()).broadcastMutation(anyString(), any(), anyString(), anyString());
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.DATATYPE_DEFINITION),
                isNull(), eq("{\"expression\":\"xsd:integer[>= 0]\"}"), eq("addDatatypeDefinition"),
                eq("http://ex.org/Age"), eq("http://ex.org/Age"), isNull(), eq("xsd:integer[>= 0]"), anyString(),
                eq(true));
    }

    @Test
    void aDraftUpdateOfAMissingDefinitionIsNotFoundAndChangesNothing() {
        when(drafts.findVisible("proj-1", "u1", "def-9")).thenReturn(Optional.empty());

        var response = controller.updateDefinition("proj-1", "def-9",
                new DatatypeDefinitionController.UpdateDatatypeDefinitionRequest(), true, "u1", "User One", null);

        assertEquals(404, response.getStatusCode().value());
        verify(exactRecorder, never()).recordAround(anyString(), anyBoolean(), anyString(), any(), any(), any());
        verify(drafts, never()).update(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aDraftDeleteKeepsTheBeforeAndAfterOfTheDraftRow() {
        DatatypeDefinitionEntity shared = entity("http://ex.org/Age", "x");
        DatatypeDefinitionEntity tombstone = entity("http://ex.org/Age", "x");
        when(drafts.findVisible("proj-1", "u1", "def-1")).thenReturn(Optional.of(shared));
        when(drafts.delete("proj-1", "u1", "def-1")).thenReturn(Optional.of(new Change(null, tombstone)));

        controller.deleteDefinition("proj-1", "def-1", true, "u1", "User One", null);

        assertEquals("deleteDatatypeDefinition", capturedDraftOps().get(0).type());
        verify(documentHistory).record(eq("proj-1"), any(RequestActor.class), eq(DocumentStore.DATATYPE_DEFINITION),
                isNull(), eq("{\"expression\":\"x\"}"), eq("deleteDatatypeDefinition"), eq("http://ex.org/Age"),
                eq("http://ex.org/Age"), isNull(), eq("x"), anyString(), eq(true));
        verify(definitionService, never()).deleteDefinition(anyString(), anyString());
    }

    @Test
    void aDraftChangeBeforeTheDraftCopyIsReadyAsksTheUserToWait() {
        var req = new DatatypeDefinitionController.CreateDatatypeDefinitionRequest();
        req.datatypeIri = "http://ex.org/Age";
        req.expression = "x";
        when(drafts.create(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DraftNotReadyException());

        var response = controller.createDefinition("proj-1", req, true, "u1", "User One", null);

        assertEquals(409, response.getStatusCode().value());
        verify(draftTracking, never()).recordDrafts(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aDraftListShowsTheUsersOwnView() {
        DatatypeDefinitionEntity mine = entity("http://ex.org/Age", "x");
        when(drafts.list("proj-1", "http://ex.org/Age", "u1")).thenReturn(List.of(mine));

        var response = controller.listDefinitions("proj-1", "http://ex.org/Age", true, "u1", null);

        assertEquals(200, response.getStatusCode().value());
        verify(definitionService, never()).listDefinitions(anyString(), anyString());
    }
}
