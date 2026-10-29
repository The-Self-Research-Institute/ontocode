package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@EnabledIfSystemProperty(named = "owl2rl.measure", matches = "true")
class Owl2RlLoadMeasureTest {

    private static String synthetic(int individuals) {
        StringBuilder text = new StringBuilder(individuals * 160);
        text.append("@prefix : <http://example.org/synth#> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n")
                .append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n<http://example.org/synth> a owl:Ontology .\n")
                .append(":knows a owl:ObjectProperty ; rdfs:domain :Person ; rdfs:range :Person .\n")
                .append(":knownBy owl:inverseOf :knows .\n:partOf a owl:TransitiveProperty .\n");
        int classes = 40;
        for (int c = 0; c < classes; c++) {
            text.append(":C").append(c).append(" a owl:Class");
            if (c > 0) {
                text.append(" ; rdfs:subClassOf :C").append((c - 1) / 2);
            }
            text.append(" .\n");
        }
        for (int i = 0; i < individuals; i++) {
            text.append(":p").append(i).append(" a :C").append(i % classes).append(" ; :knows :p").append((i + 1) % individuals)
                    .append(" ; :partOf :p").append(i / 2).append(" .\n");
        }
        return text.toString();
    }

    private static long used() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void measure(String label, String text) throws Exception {
        OWLOntology probe = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
        int axioms = probe.getAxiomCount();
        probe = null;
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 600);
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any())).thenAnswer(invocation ->
                new OntologyClientService.FetchedOntology(
                        OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text)), 1L, null));
        long baseline = used();
        AtomicLong peak = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            Runtime runtime = Runtime.getRuntime();
            while (running.get()) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        try {
            long begun = System.nanoTime();
            SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl("p", null);
            long ms = (System.nanoTime() - begun) / 1_000_000;
            running.set(false);
            System.out.println("OWL2RL-MEASURE " + label + " axioms=" + axioms + " success=" + result.success()
                    + " ms=" + ms + " derived=" + result.derivedCount() + " returned=" + result.axioms().size()
                    + " truncated=" + result.truncated() + " peakHeapOverBaselineMb=" + Math.max(0, peak.get() - baseline) / (1024 * 1024)
                    + " retainedMb=" + Math.max(0, used() - baseline) / (1024 * 1024)
                    + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                    + (result.success() ? "" : " error=" + result.errorMessage()));
        } catch (OutOfMemoryError e) {
            running.set(false);
            System.out.println("OWL2RL-MEASURE " + label + " axioms=" + axioms + " OUT_OF_MEMORY");
        }
    }

    @Test
    void measureTimeAndHeap() throws Exception {
        measure("warmup-tiny", synthetic(10));
        measure("tiny", synthetic(10));
        for (String size : System.getProperty("owl2rl.sizes", "1000,5000,17000").split(",")) {
            measure("size-" + size, synthetic(Integer.parseInt(size.trim())));
        }
    }
}
