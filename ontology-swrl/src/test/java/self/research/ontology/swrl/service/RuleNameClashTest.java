package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleNameClashTest {

    private static String triage() throws Exception {
        try (var in = RuleNameClashTest.class.getResourceAsStream("/triage_demo.owl")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private SwrlRule rule(String id, String name, String text) {
        SwrlRule rule = new SwrlRule("p1", name, text);
        rule.setId(id);
        rule.setEnabled(true);
        return rule;
    }

    private ExecutionResult execute(List<SwrlRule> rules) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(triage()));
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenReturn(new OntologyClientService.FetchedOntology(ontology, 1L, null));
        SwrlRuleRepository repository = Mockito.mock(SwrlRuleRepository.class);
        Mockito.when(repository.findByProjectIdAndEnabled("p1", true)).thenReturn(rules);
        Mockito.when(repository.save(Mockito.any(SwrlRule.class))).thenAnswer(i -> i.getArgument(0));
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", repository);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        return service.executeRules("p1");
    }

    private static final String WEEKLY = "Patient(?p) ^ daysAdmitted(?p, ?d) ^ swrlb:mod(?r, ?d, 7) ^ swrlb:equal(?r, 0) -> WeeklyReview(?p)";
    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    @Test
    void aRuleNamedLikeAClassDoesNotBreakOtherRules() throws Exception {
        ExecutionResult result = execute(List.of(
                rule("a1", "Child age", CHILD),
                rule("a2", "Weekly review days mod 7", WEEKLY)));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        assertEquals(List.of("Child age", "Weekly review days mod 7"), result.getExecutedRuleNames());
    }

    @Test
    void sameRulesUnderOtherNamesStillWork() throws Exception {
        ExecutionResult result = execute(List.of(
                rule("b1", "Age under 18", CHILD),
                rule("b2", "Weekly review days mod 7", WEEKLY)));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
    }

    @Test
    void executeWithNoSavedRulesDoesNotRunRulesEmbeddedInTheFile() throws Exception {
        ExecutionResult result = execute(List.of());
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        long fromEmbedded = result.getInferredAxioms().stream()
                .filter(a -> String.valueOf(a.getDescription()).contains("ClassAssertion")
                        && (String.valueOf(a.getDescription()).contains("#Child>") || String.valueOf(a.getDescription()).contains("#WeeklyReview>")))
                .count();
        assertEquals(0, fromEmbedded, "rules embedded in the file must not run during Execute");
    }

    @Test
    void validatingRulesThatAlsoExistInTheFileWorks() throws Exception {
        org.semanticweb.owlapi.model.OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(triage()));
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenReturn(ontology);
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        for (String text : new String[]{CHILD, WEEKLY}) {
            org.semanticweb.owlapi.model.OWLOntology fresh = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(triage()));
            Mockito.when(client.fetchOntology(Mockito.anyString())).thenReturn(fresh);
            var result = service.validateRule("p1", text);
            assertTrue(result.isValid(), "validation said: " + result.getErrorMessage());
        }
    }
}
