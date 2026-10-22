package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.controller.DocumentChangeController.DocumentChange;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.RequestActor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentChangeControllerTest {

    private static final String RULE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p1\",\"ruleName\":\"R\"}";
    private static final String OTHER_PROJECT = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p2\",\"ruleName\":\"R\"}";
    private static final String OTHER_RULE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880b\"},\"projectId\":\"p1\",\"ruleName\":\"R\"}";

    private DocumentHistory history;
    private RollbackRequestSupport support;
    private DocumentChangeController controller;

    @BeforeEach
    void setUp() {
        history = mock(DocumentHistory.class);
        support = mock(RollbackRequestSupport.class);
        controller = new DocumentChangeController(history, support);
        when(history.record(anyString(), any(), any(), any(), any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("set-1");
    }

    private static DocumentChange change(String store, String operation, String before, String after) {
        return new DocumentChange(store, operation, "R", "d", null, null, before, after);
    }

    @Test
    void aRuleChangeIsRecordedUnderTheRuleEntity() {
        ResponseEntity<Map<String, Object>> response = controller.record("p1", change("swrlRule", "updateSwrlRule", RULE, RULE), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("set-1", response.getBody().get("changeSetId"));
        verify(history).record(eq("p1"), any(RequestActor.class), eq(DocumentStore.SWRL_RULE), eq(RULE), eq(RULE),
                eq("updateSwrlRule"), eq("urn:swrlrule:6acf64ae94e5320b04bb880a"), eq("R"), any(), any(), eq("d"));
    }

    @Test
    void viewersAreRefusedBeforeAnythingIsRecorded() {
        when(support.denyIfNotAllowed(eq(List.of()), eq("p1"), any()))
                .thenReturn(ResponseEntity.status(403).body(Map.of("success", false)));

        assertEquals(403, controller.record("p1", change("swrlRule", "createSwrlRule", null, RULE), null).getStatusCode().value());
        verify(history, never()).record(anyString(), any(), any(), any(), any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aDocumentOfAnotherProjectIsRejected() {
        assertEquals(400, controller.record("p1", change("swrlRule", "createSwrlRule", null, OTHER_PROJECT), null).getStatusCode().value());
    }

    @Test
    void aBeforeAndAfterThatAreDifferentDocumentsAreRejected() {
        assertEquals(400, controller.record("p1", change("swrlRule", "updateSwrlRule", RULE, OTHER_RULE), null).getStatusCode().value());
    }

    @Test
    void onlyKnownStoresAndOperationsAreAccepted() {
        assertEquals(400, controller.record("p1", change("fuzzyRule", "createSwrlRule", null, RULE), null).getStatusCode().value());
        assertEquals(400, controller.record("p1", change("swrlRule", "dropEverything", null, RULE), null).getStatusCode().value());
        assertEquals(400, controller.record("p1", change("swrlRule", "createSwrlRule", null, null), null).getStatusCode().value());
        assertTrue(true);
    }
}
