package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.controller.DocumentChangeController.DocumentChange;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentChangeControllerTest {

    private static final String RULE = "{\"_id\":{\"$oid\":\"6acf64ae94e5320b04bb880a\"},\"projectId\":\"p1\",\"ruleName\":\"R\"}";

    private RollbackRequestSupport support;
    private DocumentChangeController controller;

    @BeforeEach
    void setUp() {
        support = mock(RollbackRequestSupport.class);
        controller = new DocumentChangeController(support);
    }

    private static DocumentChange change() {
        return new DocumentChange("swrlRule", "createSwrlRule", "R", "d", null, null, null, RULE);
    }

    @Test
    void newRuleDocumentChangesAreNoLongerRecorded() {
        ResponseEntity<Map<String, Object>> response = controller.record("p1", change(), null);

        assertEquals(410, response.getStatusCode().value());
    }

    @Test
    void viewersAreStillRefusedFirst() {
        when(support.denyIfNotAllowed(eq(List.of()), eq("p1"), any()))
                .thenReturn(ResponseEntity.status(403).body(Map.of("success", false)));

        assertEquals(403, controller.record("p1", change(), null).getStatusCode().value());
    }
}
