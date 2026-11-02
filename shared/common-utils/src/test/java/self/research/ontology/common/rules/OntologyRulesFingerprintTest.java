package self.research.ontology.common.rules;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OntologyRulesFingerprintTest {

    private static final String HEAD = "Prefix(:=<http://example.org/fp#>)\nOntology(<http://example.org/fp>\n"
            + "Declaration(Class(:A))\nDeclaration(Class(:B))\nDeclaration(Class(:C))\n";
    private static final String AB = "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n";
    private static final String BC = "DLSafeRule(Body(ClassAtom(:B Variable(:x))) Head(ClassAtom(:C Variable(:x))))\n";

    private static OWLOntology ontology(String body) throws Exception {
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(HEAD + body + ")\n"));
    }

    @Test
    void ontologyWithoutRulesIsNone() throws Exception {
        assertEquals("none", OntologyRulesFingerprint.of(ontology("")));
        assertEquals("none", OntologyRulesFingerprint.of(null));
    }

    @Test
    void sameRulesInAnyOrderGiveTheSameFingerprint() throws Exception {
        assertEquals(OntologyRulesFingerprint.of(ontology(AB + BC)), OntologyRulesFingerprint.of(ontology(BC + AB)));
    }

    @Test
    void extraRuleChangesTheFingerprint() throws Exception {
        String one = OntologyRulesFingerprint.of(ontology(AB));
        String two = OntologyRulesFingerprint.of(ontology(AB + BC));
        assertNotEquals("none", one);
        assertNotEquals(one, two);
        assertEquals(64, one.length());
    }
}
