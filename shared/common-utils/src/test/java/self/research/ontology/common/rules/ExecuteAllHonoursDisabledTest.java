package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteAllHonoursDisabledTest {

    private static final String TEXT = "Prefix(:=<http://example.org/ex#>)\nPrefix(oc:=<http://ontocode.org/ns#>)\n"
            + "Prefix(swrla:=<http://swrl.stanford.edu/ontologies/3.3/swrla.owl#>)\n"
            + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
            + "Ontology(<http://example.org/ex>\n"
            + "Declaration(Class(:Patient))\nDeclaration(Class(:Child))\nDeclaration(Class(:Senior))\nDeclaration(Class(:Critical))\n"
            + "Declaration(NamedIndividual(:ann))\nClassAssertion(:Patient :ann)\n"
            + "DLSafeRule(Annotation(rdfs:label \"Normal rule\") Body(ClassAtom(:Patient Variable(:x))) Head(ClassAtom(:Child Variable(:x))))\n"
            + "DLSafeRule(Annotation(rdfs:label \"Protege off rule\") Annotation(swrla:isRuleEnabled \"false\"^^xsd:boolean) "
            + "Body(ClassAtom(:Patient Variable(:x))) Head(ClassAtom(:Senior Variable(:x))))\n"
            + "DLSafeRule(Annotation(rdfs:label \"Own off rule\") Annotation(oc:enabled \"false\"^^xsd:boolean) "
            + "Body(ClassAtom(:Patient Variable(:x))) Head(ClassAtom(:Critical Variable(:x))))\n"
            + "DLSafeRule(Annotation(rdfs:label \"Query rule\") Annotation(swrla:isRuleEnabled \"true\"^^xsd:boolean) "
            + "Body(ClassAtom(:Patient Variable(:x))) "
            + "Head(BuiltInAtom(<http://sqwrl.stanford.edu/ontologies/built-ins/3.4/sqwrl.owl#select> Variable(:x))))\n"
            + ")\n";

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(TEXT));
    }

    @Test
    void aSqwrlQueryIsExcludedEvenWhenItsFlagSaysEnabled() throws Exception {
        OWLOntology ontology = ontology();
        long queries = ontology.axioms(AxiomType.SWRL_RULE).filter(DisabledRules::isQuery).count();
        long excluded = ontology.axioms(AxiomType.SWRL_RULE).filter(DisabledRules::isExcluded).count();

        assertEquals(1, queries);
        assertEquals(3, excluded);
        assertEquals(2, ontology.axioms(AxiomType.SWRL_RULE).filter(DisabledRules::isDisabled).count());
    }

    @Test
    void executeAllRunsOnlyTheNormalRule() throws Exception {
        OWLOntology ontology = ontology();
        assertEquals(3, DisabledRules.strip(ontology));

        RuleExecutionResult result = new RuleExecution(null, o -> new ReasonerFactory().createReasoner(o), "HermiT")
                .run(ontology, "snap", RuleExecutionLimits.defaults());

        assertEquals(List.of("Normal rule"), result.executedRules());
        assertEquals(1, result.executedRuleIds().size());
        assertTrue(result.skippedRules().isEmpty());
        String derived = result.derived().toString();
        assertTrue(derived.contains("Child"), derived);
        assertFalse(derived.contains("Senior"), derived);
        assertFalse(derived.contains("Critical"), derived);
    }

    @Test
    void theRulesFingerprintIgnoresQueriesAndDisabledRules() throws Exception {
        OWLOntology all = ontology();
        OWLOntology onlyNormal = ontology();
        DisabledRules.strip(onlyNormal);

        assertEquals(OntologyRulesFingerprint.of(onlyNormal), OntologyRulesFingerprint.of(all));
    }
}
