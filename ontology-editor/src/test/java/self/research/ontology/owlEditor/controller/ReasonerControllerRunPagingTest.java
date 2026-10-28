package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.RuleRunAccess;
import self.research.ontology.owlEditor.service.RuleRunStore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasonerControllerRunPagingTest {

    private static final String DOC = "Prefix(:=<http://example.org/n#>)\nOntology(<http://example.org/n>\nDeclaration(Class(:A))\n"
            + "Declaration(Class(:B))\nSubClassOf(:A :B)\nDeclaration(NamedIndividual(:a))\nClassAssertion(:A :a)\n)\n";

    private Map<String, Object> run(RuleRunStore store, RuleRunAccess access) throws Exception {
        ReasonerController controller = new ReasonerController();
        EditorReasonerCacheService cache = mock(EditorReasonerCacheService.class);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        ReflectionTestUtils.setField(controller, "ruleRunStore", store);
        ReflectionTestUtils.setField(controller, "ruleRunAccess", access);
        controller.useRuleStage((ontology, rules) -> Optional.of(Set.of()));
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new ByteArrayInputStream(DOC.getBytes(StandardCharsets.UTF_8)));
        when(cache.getOntology("p")).thenReturn(Optional.of(ontology));
        return controller.executeRules("p", null, false, new MockHttpServletRequest()).getBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeResponseCarriesRunIdAndCountsAndKeepsOldFields() throws Exception {
        RuleRunStore store = new RuleRunStore();
        Map<String, Object> body = run(store, new RuleRunAccess(null, true));
        assertNotNull(body.get("runId"));
        assertTrue(store.find((String) body.get("runId")).isPresent());
        Map<String, Object> counts = (Map<String, Object>) body.get("counts");
        assertEquals(body.get("conclusionCount"), counts.get("conclusions"));
        assertEquals(body.get("structureCount"), counts.get("structure"));
        assertEquals(body.get("omittedTrivialCount"), counts.get("omittedTrivial"));
        for (String key : new String[]{"inferredAxioms", "truncated", "executedRuleNames", "executedRuleIds", "inferredAxiomsCount", "status"}) {
            assertTrue(body.containsKey(key), key);
        }
    }

    @Test
    void withoutAnIdentityNoRunIsStoredButCountsStillReturn() throws Exception {
        RuleRunStore store = new RuleRunStore();
        Map<String, Object> body = run(store, new RuleRunAccess(null, false));
        assertNull(body.get("runId"));
        assertEquals(0, store.size());
        assertNotNull(body.get("counts"));
    }
}
