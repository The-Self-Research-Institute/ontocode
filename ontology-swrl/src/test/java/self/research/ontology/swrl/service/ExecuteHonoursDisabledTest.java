package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.swrl.model.ExecutionResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteHonoursDisabledTest {

    private static final String NS = "http://example.org/triage#";
    private static final OWLDataFactory F = OWLManager.getOWLDataFactory();
    private static final IRI SELECT = IRI.create("http://sqwrl.stanford.edu/ontologies/built-ins/3.4/sqwrl.owl#select");

    private static SWRLRule rule(String label, String head, SWRLAtom headAtom, Object... flags) {
        SWRLVariable p = F.getSWRLVariable(IRI.create("urn:swrl#p"));
        Set<OWLAnnotation> annotations = new LinkedHashSet<>();
        annotations.add(F.getOWLAnnotation(F.getRDFSLabel(), F.getOWLLiteral(label)));
        for (int i = 0; i < flags.length; i += 2) {
            annotations.add(F.getOWLAnnotation(F.getOWLAnnotationProperty((IRI) flags[i]), F.getOWLLiteral((Boolean) flags[i + 1])));
        }
        SWRLAtom conclusion = headAtom != null ? headAtom : F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + head)), p);
        return F.getSWRLRule(Set.of(F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + "Patient")), p)), Set.of(conclusion), annotations);
    }

    private static TriageHarness harness() throws Exception {
        TriageHarness harness = new TriageHarness();
        harness.addAxiom(rule("Normal rule", "Child", null));
        harness.addAxiom(rule("Protege off rule", "Senior", null, OntologyRules.SWRLA_ENABLED, false));
        harness.addAxiom(rule("Own off rule", "Critical", null, OntologyRules.ENABLED, false));
        harness.addAxiom(rule("Query rule", null, F.getSWRLBuiltInAtom(SELECT,
                List.of(F.getSWRLLiteralArgument(F.getOWLLiteral("x"))))));
        return harness;
    }

    @Test
    void executeAllRunsOnlyTheNormalRule() throws Exception {
        ExecutionResult result = harness().service.executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("Normal rule"), result.getExecutedRuleNames());
        assertEquals(1, result.getExecutedRuleIds().size());
        String conclusions = result.getInferredAxioms().toString();
        assertFalse(conclusions.contains("Senior"), conclusions);
        assertFalse(conclusions.contains("Critical"), conclusions);
    }

    @Test
    void executeSelectedSkipsDisabledAndQueryRules() throws Exception {
        ExecutionResult result = harness().service.executeSelectedRules("p1",
                List.of("Normal rule", "Protege off rule", "Own off rule", "Query rule"));

        assertEquals(List.of("Normal rule"), result.getExecutedRuleNames());
        assertEquals(Set.of("Protege off rule", "Own off rule", "Query rule"),
                result.getSkippedRules().stream().map(s -> s.ruleName()).collect(Collectors.toSet()));
        assertTrue(result.getSkippedRules().stream().filter(s -> s.ruleName().equals("Query rule"))
                .allMatch(s -> s.reason().contains("SQWRL")));
        assertTrue(result.getSkippedRules().stream().filter(s -> s.ruleName().endsWith("off rule"))
                .allMatch(s -> s.reason().contains("disabled")));
    }

    @Test
    void executeSelectedOfOnlyADisabledRuleRunsNothing() throws Exception {
        ExecutionResult result = harness().service.executeSelectedRules("p1", List.of("Own off rule"));

        assertFalse(result.isSuccess());
        assertTrue(result.getExecutedRuleNames() == null || result.getExecutedRuleNames().isEmpty());
        assertEquals(1, result.getSkippedRules().size());
    }
}
