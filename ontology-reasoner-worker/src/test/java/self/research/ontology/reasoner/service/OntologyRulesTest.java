package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyRulesTest {

    private static final String PROJECT = "inline";
    private static final String NS = "urn:t#";

    private static byte[] ontologyWith(String head, boolean disabled) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:t"));
        OWLClass parent = df.getOWLClass(IRI.create(NS + "Parent"));
        OWLClass elder = df.getOWLClass(IRI.create(NS + "Elder"));
        OWLClass wise = df.getOWLClass(IRI.create(NS + "Wise"));
        manager.addAxioms(ontology, Set.of(
                df.getOWLDeclarationAxiom(parent),
                df.getOWLDeclarationAxiom(elder),
                df.getOWLDeclarationAxiom(wise),
                df.getOWLClassAssertionAxiom(parent, df.getOWLNamedIndividual(IRI.create(NS + "alice")))));
        if (head != null) {
            SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
            SWRLAtom body = df.getSWRLClassAtom(parent, x);
            SWRLAtom headAtom = df.getSWRLClassAtom(df.getOWLClass(IRI.create(NS + head)), x);
            Set<OWLAnnotation> annotations = new HashSet<>();
            annotations.add(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("r1")));
            if (disabled) {
                annotations.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(IRI.create("http://ontocode.org/ns#enabled")),
                        df.getOWLLiteral("false", OWL2Datatype.XSD_BOOLEAN)));
            }
            manager.addAxiom(ontology, df.getSWRLRule(Set.of(body), Set.of(headAtom), annotations));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
        return out.toByteArray();
    }

    private OntologySessionService sessions(SnapshotTestSupport.FakeEditor editor) {
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        sessions.useRuleStage((ontology, rules) -> Optional.of(Set.of()));
        return sessions;
    }

    private ReasoningJob elderQuery() {
        return ReasoningJob.builder()
                .jobId("dl")
                .jobType(ReasoningJob.JobType.DL_QUERY)
                .projectId(PROJECT)
                .reasonerType("HERMIT")
                .expression("Elder")
                .queryTypes(List.of("instances"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> instances(Map<String, Object> result) {
        return (List<Map<String, Object>>) ((Map<String, Object>) result.get("results")).get("instances");
    }

    private Map<String, Object> runElderQuery(byte[] ontology) throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor();
        editor.published.put(PROJECT, ontology);
        OntologySessionService sessions = sessions(editor);
        return new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions)).execute(elderQuery());
    }

    @Test
    void aDlQuerySeesTheConclusionOfARuleStoredInTheOntology() throws Exception {
        Map<String, Object> result = runElderQuery(ontologyWith("Elder", false));

        assertEquals(true, result.get("success"), String.valueOf(result));
        assertEquals(1, instances(result).size(), String.valueOf(result));
        assertEquals("urn:t#alice", instances(result).get(0).get("iri"));
        assertEquals("complete", result.get("completion"));
        assertFalse(result.containsKey("downgradedWarning"));
    }

    @Test
    void aDisabledRuleIsNotApplied() throws Exception {
        Map<String, Object> result = runElderQuery(ontologyWith("Elder", true));

        assertEquals(true, result.get("success"), String.valueOf(result));
        assertTrue(instances(result).isEmpty(), String.valueOf(result));
        assertEquals("complete", result.get("completion"));
    }

    @Test
    void theCacheKeyFollowsTheRulesInTheOntologyAndIgnoresDisabledOnes() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor();
        editor.published.put(PROJECT, ontologyWith(null, false));
        OntologySessionService sessions = sessions(editor);
        long revision = sessions.currentRevision(PROJECT);
        long triples = sessions.currentTripleCount(PROJECT);
        String none = sessions.currentRulesFingerprint(PROJECT, revision, triples);

        editor.published.put(PROJECT, ontologyWith("Elder", false));
        editor.bumpRevision(PROJECT);
        String one = sessions.currentRulesFingerprint(PROJECT, sessions.currentRevision(PROJECT), sessions.currentTripleCount(PROJECT));

        editor.published.put(PROJECT, ontologyWith("Wise", false));
        editor.bumpRevision(PROJECT);
        String two = sessions.currentRulesFingerprint(PROJECT, sessions.currentRevision(PROJECT), sessions.currentTripleCount(PROJECT));

        editor.published.put(PROJECT, ontologyWith("Wise", true));
        editor.bumpRevision(PROJECT);
        String off = sessions.currentRulesFingerprint(PROJECT, sessions.currentRevision(PROJECT), sessions.currentTripleCount(PROJECT));

        assertEquals("none", none);
        assertNotEquals(none, one);
        assertNotEquals(one, two);
        assertEquals(none, off);
    }

    @Test
    void aChangedRuleIsACacheMissInTheSnapshotCache() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor();
        editor.published.put(PROJECT, ontologyWith("Elder", false));
        OntologySessionService sessions = sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot(PROJECT, "HERMIT", null);
        assertEquals("complete", first.get("completion"));
        assertEquals(1, snapshots.size());
        assertEquals(true, snapshots.snapshot(PROJECT, "HERMIT", null).get("cached"));

        editor.published.put(PROJECT, ontologyWith("Wise", false));
        editor.bumpRevision(PROJECT);

        Map<String, Object> after = snapshots.snapshot(PROJECT, "HERMIT", null);

        assertEquals(false, after.get("cached"));
        assertEquals(2, snapshots.buildCount());
    }
}
