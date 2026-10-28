package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledRulesTest {

    private static final String HEAD = "Prefix(:=<http://example.org/dr#>)\nPrefix(oc:=<http://ontocode.org/ns#>)\n"
            + "Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\nPrefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
            + "Ontology(<http://example.org/dr>\n"
            + "Declaration(Class(:A))\nDeclaration(Class(:B))\nDeclaration(Class(:C))\n";
    private static final String OFF = "DLSafeRule(Annotation(rdfs:label \"off\") Annotation(oc:enabled \"false\"^^xsd:boolean) "
            + "Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
    private static final String ON = "DLSafeRule(Annotation(rdfs:label \"on\") Annotation(oc:enabled \"true\"^^xsd:boolean) "
            + "Body(ClassAtom(:B Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n";
    private static final String PLAIN = "DLSafeRule(Annotation(rdfs:label \"plain\") "
            + "Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n";

    private static OWLOntology ontology(String body) throws Exception {
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(HEAD + body + ")\n"));
    }

    @Test
    void stripRemovesOnlyDisabledRules() throws Exception {
        OWLOntology ontology = ontology(OFF + ON + PLAIN);
        assertEquals(3, ontology.axioms(AxiomType.SWRL_RULE).count());
        assertEquals(1, DisabledRules.strip(ontology));
        assertEquals(2, ontology.axioms(AxiomType.SWRL_RULE).count());
        assertTrue(ontology.axioms(AxiomType.SWRL_RULE).noneMatch(DisabledRules::isDisabled));
    }

    @Test
    void isDisabledNeedsTheFalseAnnotation() throws Exception {
        OWLOntology ontology = ontology(OFF + ON + PLAIN);
        long disabled = ontology.axioms(AxiomType.SWRL_RULE).filter(DisabledRules::isDisabled).count();
        assertEquals(1, disabled);
        assertFalse(DisabledRules.isDisabled(null));
    }

    @Test
    void fingerprintIgnoresDisabledRules() throws Exception {
        String withoutOff = OntologyRulesFingerprint.of(ontology(ON));
        String withOff = OntologyRulesFingerprint.of(ontology(ON + OFF));
        assertEquals(withoutOff, withOff);
        assertEquals("none", OntologyRulesFingerprint.of(ontology(OFF)));
    }

    @Test
    void stripOnNullDoesNothing() {
        assertEquals(0, DisabledRules.strip(null));
    }
}
