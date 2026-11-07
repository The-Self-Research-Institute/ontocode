package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.ValidationResult;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousIndividualTest {

    private static final String DOC = """
            Prefix(:=<http://example.org/garden#>)
            Prefix(owl:=<http://www.w3.org/2002/07/owl#>)
            Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
            Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)
            Ontology(<http://example.org/garden>
            Declaration(Class(:Plant)) Declaration(Class(:Flower)) Declaration(Class(:Sprout)) Declaration(Class(:Seedling))
            Declaration(ObjectProperty(:hasPart)) Declaration(DataProperty(:height))
            Declaration(NamedIndividual(:rose))
            ClassAssertion(:Plant :rose)
            DataPropertyAssertion(:height :rose "12"^^xsd:integer)
            ObjectPropertyAssertion(:hasPart :rose _:b1)
            ClassAssertion(:Plant _:b1)
            DataPropertyAssertion(:height _:b1 "3"^^xsd:integer)
            ClassAssertion(:Sprout _:b2)
            ClassAssertion(:Sprout _:b3)
            DLSafeRule(Annotation(rdfs:label "Tall plants flower")
              Body(ClassAtom(:Plant Variable(<urn:swrl:p>)) DataPropertyAtom(:height Variable(<urn:swrl:p>) Variable(<urn:swrl:h>))
                BuiltInAtom(<http://www.w3.org/2003/11/swrlb#greaterThan> Variable(<urn:swrl:h>) "5"^^xsd:integer))
              Head(ClassAtom(:Flower Variable(<urn:swrl:p>))))
            DLSafeRule(Annotation(rdfs:label "Sprouts are seedlings")
              Body(ClassAtom(:Sprout Variable(<urn:swrl:s>)))
              Head(ClassAtom(:Seedling Variable(<urn:swrl:s>))))
            DLSafeRule(Annotation(rdfs:label "Parts are plants")
              Body(ObjectPropertyAtom(:hasPart Variable(<urn:swrl:a>) Variable(<urn:swrl:b>)))
              Head(ClassAtom(:Plant Variable(<urn:swrl:b>))))
            )
            """;

    private static OWLOntology parse() {
        try {
            OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(DOC));
            PlainLiteralNormalizer.apply(ontology);
            return ontology;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SwrlEngineService service() {
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        try {
            Mockito.when(client.fetchOntology(Mockito.anyString())).thenAnswer(i -> parse());
            Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                    .thenAnswer(i -> new OntologyClientService.FetchedOntology(parse(), 1L, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "ruleRepository", Mockito.mock(SwrlRuleRepository.class));
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        ReflectionTestUtils.setField(service, "owl2rlMaxAxioms", 1000);
        return service;
    }

    private static String shown(ExecutionResult result) {
        return text(result.getInferredAxioms());
    }

    private static String text(List<self.research.ontology.swrl.model.InferredAxiom> axioms) {
        return axioms.stream().map(a -> a.getReadable() + " " + a.getDescription() + " " + a.getSubjectIri() + " " + a.getObjectIri())
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    @Test
    void aNewRuleValidatesAndRunsOverAnOntologyWithAnonymousIndividuals() {
        SwrlEngineService service = service();
        String rule = "Plant(?p) ^ height(?p, ?h) -> Flower(?p)";

        ValidationResult validation = service.validateRule("p1", rule);
        ExecutionResult tested = service.testSingleRule("p1", rule);

        assertTrue(validation.isValid(), String.valueOf(validation.getErrorMessage()));
        assertTrue(tested.isSuccess(), String.valueOf(tested.getErrorMessage()));
        assertTrue(tested.getInferredAxiomsCount() > 0);
    }

    @Test
    void executeAllRunsTheEmbeddedRulesAndNeverLeaksTheInternalBlankNodeName() {
        ExecutionResult result = service().executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("Parts are plants", "Sprouts are seedlings", "Tall plants flower"),
                result.getExecutedRuleNames().stream().sorted().toList());
        assertTrue(result.getSkippedRules().isEmpty(), String.valueOf(result.getSkippedRules()));
        assertTrue(shown(result).contains("rose"), shown(result));
        assertFalse(shown(result).contains("urn:ontocode:blank"), shown(result));
        assertTrue(shown(result).contains("_:anonymous"), shown(result));
    }

    @Test
    void executeSelectedRunsARuleOverAClassWhoseOnlyMembersAreAnonymous() {
        SwrlEngineService service = service();
        String id = service.executeRules("p1").getExecutedRuleIds().get(0);

        ExecutionResult result = service.executeSelectedRules("p1", List.of(id));

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertNotNull(result.getExecutedRuleNames());
        assertEquals(1, result.getExecutedRuleNames().size());
        assertFalse(shown(result).contains("urn:ontocode:blank"), shown(result));
    }

    @Test
    void executeSelectedByNameRunsTheSproutRuleWhoseMatchesAreAllAnonymous() {
        ExecutionResult result = service().executeSelectedRules("p1", List.of("Sprouts are seedlings"));

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("Sprouts are seedlings"), result.getExecutedRuleNames());
        assertFalse(shown(result).contains("urn:ontocode:blank"), shown(result));
        assertTrue(shown(result).contains("Seedling> _:anonymous)"), shown(result));
    }

    @Test
    void owl2RlRunsOverAnOntologyWithAnonymousIndividuals() {
        SwrlEngineService.Owl2RlResult result = service().deriveOwl2Rl("p1", null);

        assertTrue(result.success(), String.valueOf(result.errorMessage()));
        assertFalse(text(result.axioms()).contains("urn:ontocode:blank"), text(result.axioms()));
    }
}
