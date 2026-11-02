package self.research.ontology.common;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HermitRuleCapabilityTest {

    static final Path DIR = Path.of("..", "..", "test-data", "rule-capability");

    static String outcome(String fixture, String expect) {
        try {
            String text = Files.readString(DIR.resolve(fixture), StandardCharsets.UTF_8);
            OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
            String header = text.substring(0, text.indexOf("Ontology(")) + "Ontology(<http://example.org/rc-expect>\n";
            OWLOntology expected = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(header + expect + ")\n"));
            OWLAxiom axiom = expected.getLogicalAxioms().iterator().next();
            OWLReasoner reasoner = new ReasonerFactory().createReasoner(ontology);
            try {
                return reasoner.isEntailed(axiom) ? "ENTAILED" : "NOT_ENTAILED";
            } finally {
                reasoner.dispose();
            }
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName();
        }
    }

    @Test
    void printMatrix() throws IOException {
        Properties cases = new Properties();
        cases.load(Files.newBufferedReader(DIR.resolve("cases.properties")));
        TreeMap<String, String> results = new TreeMap<>();
        for (String key : cases.stringPropertyNames()) {
            if (key.endsWith(".file")) {
                String num = key.substring(0, 2);
                results.put(num, cases.getProperty(num + ".title") + " => " + outcome(cases.getProperty(key), cases.getProperty(num + ".expect")));
            }
        }
        results.forEach((k, v) -> System.out.println("HERMIT " + k + " " + v));
        assertEquals(16, results.size());
    }
}
