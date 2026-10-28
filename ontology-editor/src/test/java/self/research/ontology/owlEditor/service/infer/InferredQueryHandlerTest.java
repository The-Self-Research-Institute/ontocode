package self.research.ontology.owlEditor.service.infer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.controller.InferredQueryHandler;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferredQueryHandlerTest {

    @Test
    void answersWithTheAdditiveInferredBlockAndTheDocumentedConflicts() {
        InferenceTestKit kit = new InferenceTestKit();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        kit.source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE, scope.graphs().get(0)));
        SparqlDatasetService dataset = Mockito.mock(SparqlDatasetService.class);
        Mockito.when(dataset.resolveUserQueryScope(InferenceFixtures.PROJECT)).thenReturn(scope);
        InferredQueryHandler handler = new InferredQueryHandler(dataset, kit.service);
        String ask = InferenceTestKit.P + "ASK { :JohnSmith :worksAt :HospitalA }";

        ResponseEntity<?> none = handler.handle(InferenceFixtures.PROJECT, ask, null, false, null);
        assertEquals(409, none.getStatusCode().value());
        Map<?, ?> noneBody = (Map<?, ?>) none.getBody();
        assertEquals(true, noneBody.get("needsReasoning"));
        assertEquals("none", noneBody.get("reason"));

        ResponseEntity<?> ok = handler.handle(InferenceFixtures.PROJECT, ask, null, true, null);
        assertEquals(200, ok.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) ok.getBody();
        assertEquals("boolean", body.get("type"));
        assertEquals(true, body.get("boolean"));
        Map<?, ?> inferred = (Map<?, ?>) body.get("inferred");
        assertEquals(true, inferred.get("used"));
        assertEquals("HermiT", inferred.get("reasoner"));
        assertEquals("public", inferred.get("scope"));
        assertEquals("v1", inferred.get("profile"));
        assertEquals(List.of("owl", "equality", "rules"), inferred.get("layers"));
        assertTrue(inferred.containsKey("snapshotId") && inferred.containsKey("computedAt") && inferred.containsKey("equality"));

        ResponseEntity<?> select = handler.handle(InferenceFixtures.PROJECT,
                InferenceTestKit.P + "SELECT ?h WHERE { :JohnSmith :worksAt ?h }", null, false, null);
        Map<?, ?> sel = (Map<?, ?>) select.getBody();
        assertEquals("select", sel.get("type"));
        assertEquals(1, ((List<?>) sel.get("results")).size());
        assertTrue(sel.containsKey("inferred") && sel.containsKey("bindings") && sel.containsKey("head"));

        kit.source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE + ":DrSmith owl:differentFrom :JohnSmith . ", scope.graphs().get(0)));
        ResponseEntity<?> bad = handler.handle(InferenceFixtures.PROJECT, ask, null, true, null);
        assertEquals(409, bad.getStatusCode().value());
        assertEquals("INCONSISTENT", ((Map<?, ?>) bad.getBody()).get("status"));
        assertTrue(((Map<?, ?>) bad.getBody()).containsKey("explanation"));
    }
}
