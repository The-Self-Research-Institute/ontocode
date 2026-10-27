package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.common.preview.RulesFingerprint;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteStampTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlRuleRepository repository = Mockito.mock(SwrlRuleRepository.class);
    private final SwrlEngineService service = new SwrlEngineService();

    ExecuteStampTest() throws Exception {
        SwrlRule rule = new SwrlRule("p1", "Age under 18", CHILD);
        rule.setId("r1");
        rule.setEnabled(true);
        Mockito.when(repository.findByProjectIdAndEnabled("p1", true)).thenReturn(List.of(rule));
        Mockito.when(repository.save(Mockito.any(SwrlRule.class))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", repository);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
    }

    private OWLOntology triage() throws Exception {
        try (var in = ExecuteStampTest.class.getResourceAsStream("/triage_demo.owl")) {
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                    new StringDocumentSource(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    void aPublicRunCarriesTheOntologyRevisionAndTheRulesFingerprint() throws Exception {
        Mockito.when(client.fetchOntologyWithStamp("p1", null))
                .thenReturn(new OntologyClientService.FetchedOntology(triage(), 42L, null));

        ExecutionResult result = service.executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        PreviewStamp stamp = result.getStamp();
        assertEquals(42L, stamp.ontologyRevision());
        assertNull(stamp.draftVersion());
        assertEquals(RulesFingerprint.of(java.util.Collections.singletonList(new String[]{"Age under 18", CHILD})), stamp.rulesFingerprint());
    }

    @Test
    void aDraftRunReadsTheDraftAndCarriesTheDraftVersion() throws Exception {
        Mockito.when(client.fetchOntologyWithStamp("p1", "u1"))
                .thenReturn(new OntologyClientService.FetchedOntology(triage(), null, "7.3"));

        ExecutionResult result = service.executeRules("p1", "u1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertNull(result.getStamp().ontologyRevision());
        assertEquals("7.3", result.getStamp().draftVersion());
        Mockito.verify(client).fetchOntologyWithStamp("p1", "u1");
    }

    @Test
    void changingARuleChangesTheStamp() throws Exception {
        Mockito.when(client.fetchOntologyWithStamp("p1", null))
                .thenReturn(new OntologyClientService.FetchedOntology(triage(), 42L, null));
        String before = service.executeRules("p1").getStamp().rulesFingerprint();

        SwrlRule edited = new SwrlRule("p1", "Age under 18", CHILD.replace("18", "21"));
        edited.setId("r1");
        edited.setEnabled(true);
        Mockito.when(repository.findByProjectIdAndEnabled("p1", true)).thenReturn(List.of(edited));
        Mockito.when(client.fetchOntologyWithStamp("p1", null))
                .thenReturn(new OntologyClientService.FetchedOntology(triage(), 42L, null));

        assertTrue(!before.equals(service.executeRules("p1").getStamp().rulesFingerprint()));
    }
}
