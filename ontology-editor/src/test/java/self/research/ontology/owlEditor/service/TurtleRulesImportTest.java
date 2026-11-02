package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TurtleRulesImportTest {

    @BeforeAll
    static void build() throws Exception {
        RulesImportExportRoundTripTest.build();
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = TurtleRulesImportTest.class.getResourceAsStream("/rules-turtle/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OWLOntology storedAndReloaded(String turtle) throws Exception {
        Model model = Rio.parse(new ByteArrayInputStream(turtle.getBytes(StandardCharsets.UTF_8)), "http://example.org/", RDFFormat.TURTLE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rio.write(model, out, RDFFormat.RDFXML);
        return RulesImportExportRoundTripTest.load(out.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, String> rulesByName(OWLOntology ontology) {
        Map<String, String> byName = new TreeMap<>();
        for (SWRLRule rule : ontology.getAxioms(AxiomType.SWRL_RULE)) {
            String label = null;
            for (OWLAnnotation annotation : rule.getAnnotations()) {
                if (annotation.getProperty().isLabel()) {
                    label = annotation.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(null);
                }
            }
            byName.put(label, rule.getAxiomWithoutAnnotations().toString());
        }
        return byName;
    }

    private static int atoms(String rule) {
        return (rule.length() - rule.replace("Atom(", "").length()) / 5;
    }

    @Test
    void rulesWrittenByOwlApiAsTurtleKeepEveryRuleAfterTheStoredTripleRoundTrip() throws Exception {
        OWLOntology source = RulesImportExportRoundTripTest.load(RulesImportExportRoundTripTest.sourceRdfXml);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.getOWLOntologyManager().saveOntology(source, new TurtleDocumentFormat(), out);

        Map<String, RulesImportExportRoundTripTest.Summary> actual =
                RulesImportExportRoundTripTest.summarise(storedAndReloaded(out.toString(StandardCharsets.UTF_8)));

        assertEquals(RulesImportExportRoundTripTest.expected, actual);
    }

    @Test
    void turtleCollectionSyntaxListsBecomeRealRules() throws Exception {
        Map<String, String> rules = rulesByName(storedAndReloaded(resource("collections.ttl")));

        assertEquals(3, rules.size(), rules.toString());
        assertEquals(4, atoms(rules.get("AdultRule")), rules.get("AdultRule"));
        assertEquals(3, atoms(rules.get("FriendRule")), rules.get("FriendRule"));
    }

    @Test
    void nestedBlankNodeAtomListsBecomeRealRules() throws Exception {
        Map<String, String> rules = rulesByName(storedAndReloaded(resource("nested.ttl")));

        assertEquals(2, rules.size(), rules.toString());
        assertEquals(4, atoms(rules.get("AdultRule")), rules.get("AdultRule"));
    }

    @Test
    void variablesThatAreNotDeclaredStillGiveCompleteRules() throws Exception {
        Map<String, String> rules = rulesByName(storedAndReloaded(resource("undeclared-vars.ttl")));

        assertEquals(3, rules.size(), rules.toString());
        assertEquals(4, atoms(rules.get("AdultRule")), rules.get("AdultRule"));
    }

    @Test
    void rulesWithoutAnIriStillImportWithTheirNames() throws Exception {
        Map<String, String> rules = rulesByName(storedAndReloaded(resource("anonymous-rules.ttl")));

        assertEquals(3, rules.size(), rules.toString());
        assertEquals(4, atoms(rules.get("AdultRule")), rules.get("AdultRule"));
    }
}
