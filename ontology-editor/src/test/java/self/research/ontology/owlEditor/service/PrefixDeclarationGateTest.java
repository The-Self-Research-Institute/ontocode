package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixDeclarationGateTest {

    @Test
    void anEditThatOnlyTouchesAxiomsIsNotTreatedAsChangingPrefixes() {
        assertFalse(PrefixScanner.mentionsDeclaration(Arrays.asList(
                "    <owl:Class rdf:about=\"http://ex.org/A\"/>", "    <owl:Class rdf:about=\"http://ex.org/B\"/>")));
        assertFalse(PrefixScanner.mentionsDeclaration(Arrays.asList(":A rdfs:subClassOf :B .", null)));
    }

    @Test
    void anEditThatTouchesADeclarationInAnySyntaxIsChecked() {
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("@prefix ex: <http://ex.org/> .", "")));
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("", "PREFIX ex: <http://ex.org/>")));
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("Prefix(ex:=<http://ex.org/>)", "")));
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("Prefix: ex: <http://ex.org/>", "")));
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("<rdf:RDF xmlns:ex=\"http://ex.org/\"", "")));
        assertTrue(PrefixScanner.mentionsDeclaration(Arrays.asList("<!ENTITY ex \"http://ex.org/\">", "")));
    }
}
