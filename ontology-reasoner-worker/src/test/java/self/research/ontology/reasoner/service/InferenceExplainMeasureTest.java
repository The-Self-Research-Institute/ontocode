package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.reasoner.service.InferenceExplainer.Outcome;
import self.research.ontology.reasoner.service.InferenceExplainer.Request;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@EnabledIfSystemProperty(named = "explain.measure", matches = "true")
class InferenceExplainMeasureTest {

    private static final String NS = "http://example.org/measure#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private final List<String> lines = new ArrayList<>();

    private void say(String line) {
        System.out.println(line);
        lines.add(line);
    }

    private record Measured(Outcome outcome, long wallMs, long peakHeapMb, long baseHeapMb) {
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private Measured measure(OWLOntology ontology, Request request, ReasonerType reasoner, long budgetMs, int maxAxioms) {
        System.gc();
        long base = usedMb();
        AtomicLong peak = new AtomicLong(base);
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread sampler = new Thread(() -> {
            while (!done.get()) {
                peak.accumulateAndGet(usedMb(), Math::max);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long begin = System.nanoTime();
        Outcome outcome = new InferenceExplainer(budgetMs, maxAxioms, maxAxioms * 10)
                .explain(ontology, request, reasoner, () -> false);
        long wall = (System.nanoTime() - begin) / 1_000_000L;
        done.set(true);
        return new Measured(outcome, wall, peak.get(), base);
    }

    private void report(String label, Measured m) {
        say(String.format("%-52s status=%-12s reasoner=%-6s fallback=%-5s ms=%6d checks=%4d axioms=%3d minimal=%-5s heapBase=%4dMB heapPeak=%4dMB (+%dMB)",
                label, m.outcome().status(), m.outcome().reasoner(), m.outcome().reasonerFallback(), m.wallMs(),
                m.outcome().entailmentChecks(), m.outcome().axioms().size(), m.outcome().minimal(),
                m.baseHeapMb(), m.peakHeapMb(), m.peakHeapMb() - m.baseHeapMb()));
    }

    private OWLOntology synthetic(int axiomTarget) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/measure"));
        Random random = new Random(42);
        int classes = Math.max(8, axiomTarget / 4);
        int individuals = axiomTarget / 2;
        int links = axiomTarget / 4;
        OWLClass[] c = new OWLClass[classes];
        for (int i = 0; i < classes; i++) {
            c[i] = df.getOWLClass(IRI.create(NS + "C" + i));
        }
        List<org.semanticweb.owlapi.model.OWLAxiom> axioms = new ArrayList<>();
        for (int i = 1; i < classes; i++) {
            axioms.add(df.getOWLSubClassOfAxiom(c[i], c[(i - 1) / 3]));
        }
        OWLObjectProperty knows = df.getOWLObjectProperty(IRI.create(NS + "knows"));
        axioms.add(df.getOWLObjectPropertyDomainAxiom(knows, c[1]));
        OWLNamedIndividual[] ind = new OWLNamedIndividual[individuals];
        for (int i = 0; i < individuals; i++) {
            ind[i] = df.getOWLNamedIndividual(IRI.create(NS + "i" + i));
            axioms.add(df.getOWLClassAssertionAxiom(c[classes / 2 + random.nextInt(classes / 2)], ind[i]));
        }
        for (int i = 0; i < links; i++) {
            axioms.add(df.getOWLObjectPropertyAssertionAxiom(knows, ind[random.nextInt(individuals)], ind[random.nextInt(individuals)]));
        }
        manager.addAxioms(ontology, axioms);
        return ontology;
    }

    @Test
    void measureSyntheticOntologies() throws Exception {
        for (int size : new int[] {1_000, 10_000, 50_000}) {
            OWLOntology ontology = synthetic(size);
            say("synthetic ontology with " + ontology.getLogicalAxiomCount() + " axioms");
            Request request = new Request("ClassAssertion", NS + "i0", RDF_TYPE, NS + "C0", null, null);
            measure(ontology, request, ReasonerType.HERMIT, 60_000, 100_000);
            report("  i0 is a C0 (subclass chain), HermiT", measure(ontology, request, ReasonerType.HERMIT, 60_000, 100_000));
            report("  i0 is a C0 (subclass chain), ELK", measure(ontology, request, ReasonerType.ELK, 60_000, 100_000));
            Request far = new Request("ClassAssertion", NS + "i1", RDF_TYPE, NS + "C1", null, null);
            report("  i1 is a C1, HermiT", measure(ontology, far, ReasonerType.HERMIT, 60_000, 100_000));
            Request nothing = new Request("ClassAssertion", NS + "i0", RDF_TYPE, NS + "C" + (size / 4 - 1), null, null);
            report("  i0 is a (unrelated leaf), not entailed, HermiT", measure(ontology, nothing, ReasonerType.HERMIT, 60_000, 100_000));
            Request domain = new Request("ClassAssertion", NS + "i" + (size / 2 - 1), RDF_TYPE, NS + "C1", null, null);
            report("  last individual is a C1, HermiT", measure(ontology, domain, ReasonerType.HERMIT, 60_000, 100_000));
        }
        save("synthetic");
    }

    private static String named(OWLOntology ontology, String shortName) {
        for (OWLEntity entity : ontology.getSignature()) {
            if (entity.getIRI().getShortForm().equals(shortName)) {
                return entity.getIRI().toString();
            }
        }
        throw new IllegalStateException("not in the ontology: " + shortName);
    }

    @Test
    @EnabledIfSystemProperty(named = "explain.measure.file", matches = ".+")
    void measureTheLocalFile() throws Exception {
        File file = new File(System.getProperty("explain.measure.file"));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file);
        DisabledRules.strip(ontology);
        say("local file: " + ontology.getLogicalAxiomCount() + " logical axioms");
        List<Object[]> facts = List.of(
                new Object[] {"Customer2 is a Person", new Request("ClassAssertion", named(ontology, "Customer2"), RDF_TYPE, named(ontology, "Person"), null, null)},
                new Object[] {"TestTunaTopping1 is a MeatTopping or SeafoodTopping", new Request("ClassAssertion", named(ontology, "TestTunaTopping1"), RDF_TYPE, null, null, null,
                        List.of(named(ontology, "MeatTopping"), named(ontology, "SeafoodTopping")), "UNION")},
                new Object[] {"AmericanaHotPizza1 is a NamedPizza", new Request("ClassAssertion", named(ontology, "AmericanaHotPizza1"), RDF_TYPE, named(ontology, "NamedPizza"), null, null)},
                new Object[] {"Chef is a Person", new Request("ClassAssertion", named(ontology, "Chef"), RDF_TYPE, named(ontology, "Person"), null, null)});
        for (Object[] fact : facts) {
            for (ReasonerType type : new ReasonerType[] {ReasonerType.HERMIT, ReasonerType.ELK}) {
                Measured measured = measure(ontology, (Request) fact[1], type, 30_000, 50_000);
                report("  " + fact[0] + " [" + type.getDisplayName() + "]", measured);
                if (type == ReasonerType.HERMIT) {
                    InferenceExplanationView view = new InferenceExplanationView(ontology,
                            (entity, o) -> entity.getIRI().getShortForm());
                    for (org.semanticweb.owlapi.model.OWLAxiom axiom : measured.outcome().axioms()) {
                        say("      " + view.axiom(axiom, "ASSERTED").get("text"));
                    }
                    if (measured.outcome().axioms().isEmpty()) {
                        say("      " + measured.outcome().message());
                    }
                }
            }
        }
        save("localfile");
    }

    private void save(String name) throws Exception {
        Path out = Path.of("target", "explain-measure-" + name + ".txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines, StandardCharsets.UTF_8);
    }
}
