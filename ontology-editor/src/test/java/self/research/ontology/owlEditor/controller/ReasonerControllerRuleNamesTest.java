package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasonerControllerRuleNamesTest {

    private static final String DECLS = "Prefix(:=<http://example.org/n#>)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
            + "Ontology(<http://example.org/n>\nDeclaration(Class(:A))\nDeclaration(Class(:B))\nDeclaration(Class(:C))\n"
            + "Declaration(NamedIndividual(:a))\nClassAssertion(:A :a)\n";

    private ReasonerController controller;
    private EditorReasonerCacheService cache;

    @BeforeEach
    void setUp() {
        controller = new ReasonerController();
        cache = mock(EditorReasonerCacheService.class);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        controller.useRuleStage((ontology, rules) -> Optional.of(Set.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String rules) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new ByteArrayInputStream((DECLS + rules + ")\n").getBytes(StandardCharsets.UTF_8)));
        when(cache.getOntology("p")).thenReturn(Optional.of(ontology));
        return controller.executeRules("p", null, false, new MockHttpServletRequest()).getBody();
    }

    @Test
    void aLabelledRuleIsNamedByItsLabel() throws Exception {
        Map<String, Object> body = execute("DLSafeRule(Annotation(rdfs:label \"Adults are people\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n");
        assertEquals(List.of("Adults are people"), body.get("executedRuleNames"));
        assertEquals(List.of("Adults are people"), body.get("executedRuleIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnlabelledRuleShowsRuleAndHexNeverTheInternalId() throws Exception {
        Map<String, Object> body = execute("DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n");
        List<String> names = (List<String>) body.get("executedRuleNames");
        List<String> ids = (List<String>) body.get("executedRuleIds");
        assertEquals(1, names.size());
        assertTrue(names.get(0).matches("Rule [0-9a-f]{8}"), names.get(0));
        assertFalse(names.get(0).contains("rule-"));
        assertTrue(ids.get(0).matches("rule-[0-9a-f]{8}"), ids.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateLabelsKeepNamesAndIdsParallel() throws Exception {
        Map<String, Object> body = execute(
                "DLSafeRule(Annotation(rdfs:label \"Same\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n"
                        + "DLSafeRule(Annotation(rdfs:label \"Same\") Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n");
        List<String> names = (List<String>) body.get("executedRuleNames");
        List<String> ids = (List<String>) body.get("executedRuleIds");
        assertEquals(List.of("Same", "Same"), names);
        assertEquals(2, ids.size());
        assertEquals(2, body.get("totalRulesExecuted"));
    }
}
