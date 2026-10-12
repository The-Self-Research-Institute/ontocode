package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class EntityRelationControllerTest {

    @Mock
    private OntologyMutationService mutationService;
    @Mock
    private DraftTrackingService draftTrackingService;
    @Mock
    private OntologyHistoryService historyService;

    private EntityRelationController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new EntityRelationController(mutationService, draftTrackingService, historyService);
    }

    @Test
    void editRelationRecordsDraftHistorySynchronously() {
        var req = new EntityRelationController.RelationRequest(
                "add", "http://ex.org/Pizza", "domain", "http://ex.org/Topping", null,
                "u1", "User One", null, null, null, null);

        controller.editRelation("proj-1", req, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MutationOp>> opsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).recordGroupedMutations(
                anyString(), anyString(), anyString(), opsCaptor.capture(), org.mockito.ArgumentMatchers.eq(true));
        assertEquals(1, opsCaptor.getValue().size());
        assertEquals("addPropertyDomain", opsCaptor.getValue().get(0).type());
    }

    @Test
    void editRelationRecordsPublicHistoryAsynchronously() {
        var req = new EntityRelationController.RelationRequest(
                "add", "http://ex.org/Pizza", "domain", "http://ex.org/Topping", null,
                "u1", "User One", null, null, null, null);

        controller.editRelation("proj-1", req, false);

        verify(historyService, timeout(2000)).recordGroupedMutations(
                anyString(), anyString(), anyString(), anyList(), org.mockito.ArgumentMatchers.eq(false));
    }
}
