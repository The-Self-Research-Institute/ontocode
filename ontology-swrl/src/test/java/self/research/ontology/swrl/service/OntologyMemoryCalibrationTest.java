package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.factory.SWRLAPIFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@EnabledIfSystemProperty(named = "calibrate", matches = "true")
class OntologyMemoryCalibrationTest {

    private static long used() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(150);
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static byte[] turtle(int triples) {
        StringBuilder text = new StringBuilder(triples * 40);
        text.append("@prefix : <http://example.org/cal#> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n")
                .append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n<http://example.org/cal> a owl:Ontology .\n")
                .append(":Person a owl:Class .\n:Adult a owl:Class .\n:hasAge a owl:DatatypeProperty .\n:knows a owl:ObjectProperty .\n");
        int individuals = triples / 3;
        for (int i = 0; i < individuals; i++) {
            text.append(":person").append(i).append(" a :Person , owl:NamedIndividual ; :hasAge ").append(i % 100)
                    .append(" ; :knows :person").append((i + 1) % individuals).append(" .\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void measureHeapPerAxiom() throws Exception {
        System.out.println("CALIBRATION maxMemoryMb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        for (String size : System.getProperty("calibrate.sizes", "50000,100000,200000").split(",")) {
            int triples = Integer.parseInt(size.trim());
            try {
                measure(triples);
            } catch (OutOfMemoryError e) {
                System.out.println("CALIBRATION triples=" + triples + " OUT_OF_MEMORY");
            }
        }
    }

    private static void measure(int triples) throws Exception {
        byte[] text = turtle(triples);
        long baseline = used();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new ByteArrayInputStream(text));
        int axioms = ontology.getAxiomCount();
        PlainLiteralNormalizer.apply(ontology);
        long parsed = used();
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        engine.createSWRLRule("adult", ":Person(?p) -> :Adult(?p)");
        long withEngine = used();
        engine.infer();
        int inferred = engine.getInferredOWLAxioms().size();
        long afterInfer = used();
        System.out.printf("CALIBRATION triples=%d axioms=%d textBytes=%d textBytesPerAxiom=%.1f parseBytesPerAxiom=%.0f engineBytesPerAxiom=%.0f inferBytesPerAxiom=%.0f inferred=%d%n",
                triples, axioms, text.length, (double) text.length / axioms, (double) (parsed - baseline) / axioms,
                (double) (withEngine - baseline) / axioms, (double) (afterInfer - baseline) / axioms, inferred);
    }
}
