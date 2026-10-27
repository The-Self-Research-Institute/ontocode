package self.research.ontology.swrl.service;

import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class TriageHarness {

    static final String PROJECT = "p1";

    final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    final SwrlRuleRepository repository = Mockito.mock(SwrlRuleRepository.class);
    final SwrlEngineService service = new SwrlEngineService();

    TriageHarness(SwrlRule... enabledRules) throws Exception {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", repository);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        Mockito.when(repository.findByProjectIdAndEnabled(PROJECT, true)).thenReturn(Arrays.asList(enabledRules));
        Mockito.when(repository.save(Mockito.any(SwrlRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> triage());
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(triage(), 1L, null));
    }

    static OWLOntology triage() throws Exception {
        try (var in = TriageHarness.class.getResourceAsStream("/triage_demo.owl")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
        }
    }

    static SwrlRule rule(String id, String name, String text) {
        SwrlRule rule = new SwrlRule(PROJECT, name, text);
        rule.setId(id);
        rule.setEnabled(true);
        return rule;
    }
}
