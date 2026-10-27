package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedRulesTest {

    private static final String PROJECT = "saved";

    private static final String RULE_ONE = ruleOntology("Elder");
    private static final String RULE_TWO = ruleOntology("Parent");

    private static String ruleOntology(String head) {
        return "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<urn:rules>\n"
                + "Declaration(Class(<urn:t#Parent>))\n"
                + "Declaration(Class(<urn:t#Elder>))\n"
                + "Declaration(Class(<urn:t#Wise>))\n"
                + "DLSafeRule(Annotation(rdfs:label \"r1\") Body(ClassAtom(<urn:t#Parent> Variable(<urn:t#x>))) "
                + "Head(ClassAtom(<urn:t#" + head + "> Variable(<urn:t#x>))))\n"
                + ")";
    }

    private static byte[] baseOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("urn:t"));
        OWLClass parent = df.getOWLClass(IRI.create("urn:t#Parent"));
        OWLClass elder = df.getOWLClass(IRI.create("urn:t#Elder"));
        manager.addAxioms(ontology, Set.of(
                df.getOWLDeclarationAxiom(parent),
                df.getOWLDeclarationAxiom(elder),
                df.getOWLClassAssertionAxiom(parent, df.getOWLNamedIndividual(IRI.create("urn:t#alice")))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new NTriplesDocumentFormat(), out);
        return out.toByteArray();
    }

    private static final class Remote {
        final AtomicReference<String> body = new AtomicReference<>("");
        final AtomicReference<String> fingerprint = new AtomicReference<>("");
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicInteger calls = new AtomicInteger();

        SavedRulesClient client() {
            return new SavedRulesClient((url, token) -> {
                calls.incrementAndGet();
                return new SavedRulesClient.HttpAnswer(status.get(),
                        Map.of("X-Ontocode-Rules-Fingerprint", fingerprint.get()), body.get());
            }, "http://saved-rules.test", "token");
        }
    }

    private OntologySessionService sessions(Remote remote) throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor();
        editor.published.put(PROJECT, baseOntology());
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        sessions.useRuleStage((ontology, rules) -> Optional.of(Set.of()));
        sessions.useSavedRulesClient(remote.client());
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

    @Test
    void aDlQuerySeesTheConclusionOfASavedRule() throws Exception {
        Remote remote = new Remote();
        remote.body.set(RULE_ONE);
        remote.fingerprint.set("fp-1");
        OntologySessionService sessions = sessions(remote);
        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions));

        Map<String, Object> result = executor.execute(elderQuery());

        assertEquals(true, result.get("success"), String.valueOf(result));
        assertEquals(1, instances(result).size(), String.valueOf(result));
        assertEquals("urn:t#alice", instances(result).get(0).get("iri"));
        assertEquals("complete", result.get("completion"));
    }

    @Test
    void withoutSavedRulesTheQueryBehavesAsBefore() throws Exception {
        Remote remote = new Remote();
        OntologySessionService sessions = sessions(remote);
        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions));

        Map<String, Object> result = executor.execute(elderQuery());

        assertEquals(true, result.get("success"), String.valueOf(result));
        assertTrue(instances(result).isEmpty());
        assertEquals("complete", result.get("completion"));
        assertFalse(result.containsKey("downgradedWarning"));
    }

    @Test
    void anUnreachableRuleServiceMakesTheSessionPartialWithTheNoteAndIsNotCached() throws Exception {
        Remote remote = new Remote();
        remote.status.set(500);
        OntologySessionService sessions = sessions(remote);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        try (OntologySessionService.ReasoningSession session = sessions.openSnapshotSession(PROJECT, ReasonerType.HERMIT, null)) {
            assertFalse(session.rulesComplete());
            assertTrue(session.downgradedWarning().contains(SavedRulesClient.UNAVAILABLE_NOTE), session.downgradedWarning());
        }
        Map<String, Object> first = snapshots.snapshot(PROJECT, "HERMIT", null);
        snapshots.snapshot(PROJECT, "HERMIT", null);

        assertEquals("partial", first.get("completion"));
        assertEquals(0, snapshots.size());
        assertEquals(2, snapshots.buildCount());
    }

    @Test
    void theCacheKeyDiffersWithAndWithoutSavedRulesAndWhenTheyChange() throws Exception {
        Remote remote = new Remote();
        OntologySessionService sessions = sessions(remote);
        long revision = sessions.currentRevision(PROJECT);
        long triples = sessions.currentTripleCount(PROJECT);

        String none = sessions.currentRulesFingerprint(PROJECT, revision, triples);

        remote.body.set(RULE_ONE);
        remote.fingerprint.set("fp-1");
        sessions.useSavedRulesClient(remote.client());
        String one = sessions.currentRulesFingerprint(PROJECT, revision, triples);

        remote.body.set(RULE_TWO);
        remote.fingerprint.set("fp-2");
        sessions.useSavedRulesClient(remote.client());
        String two = sessions.currentRulesFingerprint(PROJECT, revision, triples);

        assertNotEquals(none, one);
        assertNotEquals(one, two);
        assertNotEquals(none, two);
    }

    @Test
    void theFingerprintIsFetchedOncePerFiveSeconds() throws Exception {
        Remote remote = new Remote();
        remote.body.set(RULE_ONE);
        remote.fingerprint.set("fp-1");
        OntologySessionService sessions = sessions(remote);
        long revision = sessions.currentRevision(PROJECT);
        long triples = sessions.currentTripleCount(PROJECT);

        sessions.currentRulesFingerprint(PROJECT, revision, triples);
        sessions.currentRulesFingerprint(PROJECT, revision, triples);
        sessions.currentRulesFingerprint(PROJECT, revision, triples);

        assertEquals(1, remote.calls.get());
    }

    @Test
    void aChangedSavedRuleSetIsACacheMissInMemoryAndInTheStoredSnapshot() throws Exception {
        Remote remote = new Remote();
        remote.body.set(RULE_ONE);
        remote.fingerprint.set("fp-1");
        OntologySessionService sessions = sessions(remote);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);

        Map<String, Object> first = snapshots.snapshot(PROJECT, "HERMIT", null);
        assertEquals("complete", first.get("completion"));
        assertEquals(1, snapshots.size());
        assertEquals(true, snapshots.snapshot(PROJECT, "HERMIT", null).get("cached"));

        remote.body.set(RULE_TWO);
        remote.fingerprint.set("fp-2");
        sessions.useSavedRulesClient(remote.client());

        Map<String, Object> after = snapshots.snapshot(PROJECT, "HERMIT", null);

        assertEquals(false, after.get("cached"));
        assertEquals(2, snapshots.buildCount());
        assertEquals(2, snapshots.size());
        assertTrue(snapshots.cached(PROJECT, "HERMIT", sessions.currentTripleCount(PROJECT)).isPresent());

        remote.body.set("");
        remote.fingerprint.set("");
        sessions.useSavedRulesClient(remote.client());
        assertTrue(snapshots.cached(PROJECT, "HERMIT", sessions.currentTripleCount(PROJECT)).isEmpty());
    }
}
