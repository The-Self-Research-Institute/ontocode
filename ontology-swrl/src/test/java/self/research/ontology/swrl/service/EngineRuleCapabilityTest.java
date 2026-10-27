package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineRuleCapabilityTest {

    static final Path DIR = Path.of("..", "test-data", "rule-capability");

    static String outcome(String fixture, String expect) {
        try {
            String text = Files.readString(DIR.resolve(fixture), StandardCharsets.UTF_8);
            String header = text.substring(0, text.indexOf("Ontology(")) + "Ontology(<http://example.org/rc-expect>\n";
            OWLOntology expected = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(header + expect + ")\n"));
            OWLAxiom axiom = expected.getLogicalAxioms().iterator().next();
            SwrlEngineService service = new SwrlEngineService();
            ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
            ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
            String answer = service.inferOntologyRules(text);
            OWLOntology inferred = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(answer));
            boolean found = inferred.getAxioms().stream().anyMatch(a -> a.equals(axiom)
                    || a.toString().equals(axiom.toString())
                    || a.getAxiomWithoutAnnotations().toString().equals(axiom.toString()));
            return found ? "ENTAILED" : "NOT_ENTAILED(" + inferred.getAxiomCount() + " axioms returned)";
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).replace('\n', ' ');
        }
    }

    @Test
    void printMatrix() throws Exception {
        Properties cases = new Properties();
        cases.load(Files.newBufferedReader(DIR.resolve("cases.properties")));
        TreeMap<String, String> results = new TreeMap<>();
        for (String key : cases.stringPropertyNames()) {
            if (key.endsWith(".file")) {
                String num = key.substring(0, 2);
                results.put(num, cases.getProperty(num + ".title") + " => " + outcome(cases.getProperty(key), cases.getProperty(num + ".expect")));
            }
        }
        results.forEach((k, v) -> System.out.println("ENGINE " + k + " " + v));
        assertEquals(16, results.size());
    }
}
