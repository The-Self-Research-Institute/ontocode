package self.research.ontology.reasoner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import self.research.ontology.common.ReasoningSnapshot;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

class ElkScaleBenchmarkTest {

    static OWLOntology synthetic(OWLOntologyManager manager, int axioms) throws Exception {
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(IRI.create("http://bench/o" + axioms));
        String ns = "http://bench/o#";
        Random rnd = new Random(42);
        int classCount = Math.max(50, axioms / 10);
        int annotations = (int) (axioms * 0.3);
        int existentials = axioms / 10;
        int individuals = Boolean.parseBoolean(System.getProperty("bench.inds", "true")) ? axioms / 4 : 0;
        boolean domainRange = Boolean.parseBoolean(System.getProperty("bench.dr", "true"));
        int props = 40;
        double branching = Math.pow(classCount, 1.0 / 8);

        List<OWLClass> classes = new ArrayList<>();
        List<OWLAxiom> all = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            OWLClass c = df.getOWLClass(IRI.create(ns + "C" + i));
            classes.add(c);
            if (i > 0) {
                int parent = (int) ((i - 1) / branching);
                all.add(df.getOWLSubClassOfAxiom(c, classes.get(Math.min(parent, i - 1))));
            }
            all.add(df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(), df.getOWLLiteral("Class " + i)));
        }
        List<OWLObjectProperty> properties = new ArrayList<>();
        for (int p = 0; p < props; p++) {
            OWLObjectProperty op = df.getOWLObjectProperty(IRI.create(ns + "p" + p));
            properties.add(op);
            if (domainRange) {
                all.add(df.getOWLObjectPropertyDomainAxiom(op, classes.get(rnd.nextInt(classCount))));
                all.add(df.getOWLObjectPropertyRangeAxiom(op, classes.get(rnd.nextInt(classCount))));
            }
            if (p > 0 && p % 4 == 0) {
                all.add(df.getOWLSubObjectPropertyOfAxiom(op, properties.get(p - 1)));
            }
        }
        for (int e = 0; e < existentials; e++) {
            all.add(df.getOWLSubClassOfAxiom(classes.get(rnd.nextInt(classCount)),
                    df.getOWLObjectSomeValuesFrom(properties.get(rnd.nextInt(props)), classes.get(rnd.nextInt(classCount)))));
        }
        List<OWLNamedIndividual> inds = new ArrayList<>();
        for (int i = 0; i < individuals; i++) {
            OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(ns + "i" + i));
            inds.add(ind);
            all.add(df.getOWLClassAssertionAxiom(classes.get(rnd.nextInt(classCount)), ind));
            if (i > 0) {
                all.add(df.getOWLObjectPropertyAssertionAxiom(properties.get(rnd.nextInt(props)), ind,
                        inds.get(rnd.nextInt(i))));
            }
        }
        int extra = annotations - classCount;
        for (int a = 0; a < extra; a++) {
            IRI target = (a % 2 == 0 || inds.isEmpty()) ? classes.get(rnd.nextInt(classCount)).getIRI()
                    : inds.get(rnd.nextInt(inds.size())).getIRI();
            all.add(df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), target, df.getOWLLiteral("Comment " + a)));
        }
        manager.addAxioms(ont, all.stream());
        return ont;
    }

    static long usedMb() {
        System.gc();
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) >> 20;
    }

    static void row(String s) {
        System.out.println("BENCH " + s);
    }

    @Test
    void elkScaling() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench"));
        String[] sizes = System.getProperty("bench.sizes", "10000,50000,150000,500000").split(",");
        row("maxHeapMb=" + (Runtime.getRuntime().maxMemory() >> 20) + " cpus=" + Runtime.getRuntime().availableProcessors());
        for (String s : sizes) {
            int n = Integer.parseInt(s.trim());
            try {
                runSize(n);
            } catch (Throwable t) {
                row(n + " FAILED " + t);
                t.printStackTrace(System.out);
            }
        }
    }

    private void runSize(int n) throws Exception {
        long t0 = System.currentTimeMillis();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = synthetic(manager, n);
        long buildMs = System.currentTimeMillis() - t0;
        int axioms = ont.getAxiomCount();

        t0 = System.currentTimeMillis();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ont, new NTriplesDocumentFormat(), out);
        byte[] nt = out.toByteArray();
        long saveMs = System.currentTimeMillis() - t0;
        long triples = new String(nt, java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count();
        row(n + " axioms=" + axioms + " triples=" + triples + " bytesMB=" + (nt.length >> 20) + " build=" + buildMs + "ms save=" + saveMs + "ms");

        t0 = System.currentTimeMillis();
        OWLReasoner elk = new ElkReasonerFactory().createReasoner(ont, new SimpleConfiguration(1_800_000));
        long createMs = System.currentTimeMillis() - t0;
        t0 = System.currentTimeMillis();
        boolean consistent = elk.isConsistent();
        long consMs = System.currentTimeMillis() - t0;
        t0 = System.currentTimeMillis();
        elk.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        long classifyMs = System.currentTimeMillis() - t0;
        t0 = System.currentTimeMillis();
        elk.precomputeInferences(InferenceType.CLASS_ASSERTIONS);
        long realizeMs = System.currentTimeMillis() - t0;
        row(n + " ELK-only create=" + createMs + " isConsistent(" + consistent + ")=" + consMs + " CLASS_HIERARCHY=" + classifyMs
                + " CLASS_ASSERTIONS=" + realizeMs + " heapMB=" + usedMb());
        elk.dispose();

        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor();
        editor.published.put("bench", nt);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);

        for (ReasonerType type : new ReasonerType[]{ReasonerType.ELK}) {
            t0 = System.currentTimeMillis();
            try (OntologySessionService.ReasoningSession session = sessions.openSnapshotSession("bench", type, null)) {
                long openMs = System.currentTimeMillis() - t0;
                row(n + " " + type + " openSnapshotSession=" + openMs + "ms (load+rules+reasoners) propertyType="
                        + session.propertyReasonerType() + " heapMB=" + usedMb());
                t0 = System.currentTimeMillis();
                Map<String, Object> built = ReasoningSnapshot.build(session.ontology(), session.reasoner(), session.propertyReasoner());
                long buildSnapMs = System.currentTimeMillis() - t0;
                t0 = System.currentTimeMillis();
                byte[] json = new ObjectMapper().writeValueAsBytes(built);
                long jsonMs = System.currentTimeMillis() - t0;
                Object inferred = built.get("inferredAxioms") instanceof Map<?, ?> m ? m.get("durationMs") : null;
                row(n + " " + type + " snapshot.build=" + buildSnapMs + "ms classificationMs=" + built.get("classificationMs")
                        + " inferredAxiomsMs=" + inferred + " json=" + jsonMs + "ms jsonKB=" + (json.length >> 10)
                        + " heapMB=" + usedMb());
            }
        }
    }
}
