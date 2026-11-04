package self.research.ontology.owlEditor.service.infer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.SWRLAtom;
import self.research.ontology.owlEditor.service.SparqlQueryRejectedException;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceScenarioTest {

    static final String DATA = ":EnvironmentalMeasurementDatum a owl:Class . "
            + ":MeasurementDatumMid a owl:Class ; rdfs:subClassOf :EnvironmentalMeasurementDatum . "
            + ":ScalarEnvironmentalMeasurementDatum a owl:Class ; rdfs:subClassOf :MeasurementDatumMid . "
            + ":Person a owl:Class . :hasExposureSubject a owl:ObjectProperty . :hasWindowMatchedHealthReference a owl:ObjectProperty . "
            + ":PersonalExposureDatum a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( :EnvironmentalMeasurementDatum "
            + "[ a owl:Restriction ; owl:onProperty :hasExposureSubject ; owl:someValuesFrom :Person ] ) ] . "
            + ":d1 a owl:NamedIndividual , :ScalarEnvironmentalMeasurementDatum ; :hasExposureSubject :p1 . :p1 a owl:NamedIndividual , :Person . ";

    InferenceTestKit kit;
    SparqlQueryScope scope;

    static IRI iri(String local) {
        return IRI.create(InferenceFixtures.E + local);
    }

    static InferenceFixtures.RuleSpec rule(String label, boolean enabled) {
        return new InferenceFixtures.RuleSpec(
                (OWLDataFactory df) -> Set.<SWRLAtom>of(
                        df.getSWRLClassAtom(df.getOWLClass(iri("PersonalExposureDatum")), df.getSWRLVariable(IRI.create("urn:swrl:var#x"))),
                        df.getSWRLObjectPropertyAtom(df.getOWLObjectProperty(iri("hasExposureSubject")),
                                df.getSWRLVariable(IRI.create("urn:swrl:var#x")), df.getSWRLVariable(IRI.create("urn:swrl:var#p")))),
                df -> Set.<SWRLAtom>of(df.getSWRLObjectPropertyAtom(df.getOWLObjectProperty(iri("hasWindowMatchedHealthReference")),
                        df.getSWRLVariable(IRI.create("urn:swrl:var#x")), df.getSWRLVariable(IRI.create("urn:swrl:var#p")))),
                label, enabled);
    }

    void load(SparqlQueryScope target, InferenceFixtures.RuleSpec... rules) {
        List<org.eclipse.rdf4j.model.Statement> all = InferenceFixtures.turtle(DATA, target.graphs().get(0));
        if (rules.length > 0) {
            all.addAll(InferenceFixtures.rules(target.graphs().get(0), List.of(rules)));
        }
        kit.source.set(target, all);
    }

    @BeforeEach
    void setUp() {
        kit = new InferenceTestKit();
        scope = InferenceFixtures.publicScope();
    }

    @Test
    void theClientCaseIsFalseAssertedOnlyAndTrueWhenInferredIsIncluded() {
        load(scope, rule("window rule", true));
        String subclass = ":d1 a :EnvironmentalMeasurementDatum";
        String equivalent = ":d1 a :PersonalExposureDatum";
        String derived = ":d1 :hasWindowMatchedHealthReference :p1";
        for (String q : List.of(subclass, equivalent, derived)) {
            assertFalse(kit.askAsserted(scope, q), q);
            assertTrue(kit.askInferred(scope, null, q, true), q);
        }
    }

    @Test
    void aDisabledRuleIsNotApplied() {
        load(scope, rule("window rule", false));
        assertTrue(kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", true));
        assertFalse(kit.askInferred(scope, null, ":d1 :hasWindowMatchedHealthReference :p1", false));
    }

    @Test
    void renamingARuleKeepsTheRulesFingerprintButEnablingChangesIt() {
        InferenceCapture capture = new InferenceCapture(kit.source, 500_000);
        load(scope, rule("first name", true));
        String a = capture.capture(scope, null).rulesFingerprint();
        load(scope, rule("a completely different name", true));
        String b = capture.capture(scope, null).rulesFingerprint();
        load(scope, rule("first name", false));
        String c = capture.capture(scope, null).rulesFingerprint();
        load(scope);
        String d = capture.capture(scope, null).rulesFingerprint();
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertEquals(c, d);
    }

    @Test
    void draftInferenceIsNeverVisibleToPublicOrToAnotherUser() {
        SparqlQueryScope draft = InferenceFixtures.draftScope("u1");
        SparqlQueryScope otherDraft = InferenceFixtures.draftScope("u2");
        load(scope);
        load(draft, rule("window rule", true));
        load(otherDraft);
        assertTrue(kit.askInferred(draft, "u1", ":d1 :hasWindowMatchedHealthReference :p1", true));
        InferenceException publicMissing = assertThrows(InferenceException.class,
                () -> kit.askInferred(scope, null, ":d1 :hasWindowMatchedHealthReference :p1", false));
        assertEquals(InferenceException.NONE, publicMissing.reason());
        InferenceException otherMissing = assertThrows(InferenceException.class,
                () -> kit.askInferred(otherDraft, "u2", ":d1 :hasWindowMatchedHealthReference :p1", false));
        assertEquals(InferenceException.NONE, otherMissing.reason());
        assertFalse(kit.askInferred(scope, null, ":d1 :hasWindowMatchedHealthReference :p1", true));
        assertFalse(kit.askInferred(otherDraft, "u2", ":d1 :hasWindowMatchedHealthReference :p1", true));
        assertTrue(kit.askInferred(draft, "u1", ":d1 :hasWindowMatchedHealthReference :p1", false));
        String draftId = (String) kit.service.query(draft, "u1", InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");
        String publicId = (String) kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");
        assertNotEquals(draftId, publicId);
        assertEquals("draft", kit.service.query(draft, "u1", InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("scope"));
        assertEquals("public", kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("scope"));
    }

    @Test
    void aQueryCannotNameAnotherScopesGraph() {
        load(scope);
        String draftGraph = InferenceFixtures.draftScope("u1").graphs().get(0);
        assertThrows(SparqlQueryRejectedException.class, () -> kit.service.query(scope, null,
                InferenceTestKit.P + "SELECT * WHERE { GRAPH <" + draftGraph + "> { ?s ?p ?o } }", true, 10));
    }

    @Test
    void graphVariableOnlySeesTheAuthorizedGraphNames() {
        load(scope, rule("window rule", true));
        List<List<String>> graphs = kit.rows(scope, null, "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }", "g");
        assertEquals(List.of(List.of(scope.graphs().get(0))), graphs);
        assertTrue(kit.askInferred(scope, null, "GRAPH ?g { :d1 a :EnvironmentalMeasurementDatum }", false));
    }

    @Test
    void noSnapshotThenStaleThenRefresh() {
        load(scope);
        InferenceException none = assertThrows(InferenceException.class, () -> kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", false));
        assertEquals(InferenceException.NONE, none.reason());
        assertFalse(((Boolean) kit.service.status(scope, null).get("available")));
        assertTrue(kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", true));
        assertTrue((Boolean) kit.service.status(scope, null).get("available"));
        kit.source.bump(scope);
        InferenceException stale = assertThrows(InferenceException.class, () -> kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", false));
        assertEquals(InferenceException.STALE, stale.reason());
        assertTrue(stale.computedAt() != null);
        assertTrue((Boolean) kit.service.status(scope, null).get("stale"));
        assertTrue(kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", true));
        assertFalse((Boolean) kit.service.status(scope, null).get("stale"));
    }

    @Test
    void anEditAfterReasoningChangesTheSnapshotIdentity() {
        load(scope);
        kit.askInferred(scope, null, ":d1 a :PersonalExposureDatum", true);
        String before = (String) kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");
        List<org.eclipse.rdf4j.model.Statement> edited = InferenceFixtures.turtle(DATA + ":d2 a :ScalarEnvironmentalMeasurementDatum . ", scope.graphs().get(0));
        kit.source.set(scope, edited);
        assertTrue(kit.askInferred(scope, null, ":d2 a :EnvironmentalMeasurementDatum", true));
        String after = (String) kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");
        assertNotEquals(before, after);
    }

    @Test
    void sizeGuardRefusesAboveTheLimitAndStatusSaysSo() {
        kit = new InferenceTestKit(10, 200_000, 5);
        load(scope);
        InferenceException e = assertThrows(InferenceException.class, () -> kit.askInferred(scope, null, ":d1 a :Person", true));
        assertEquals(InferenceException.TOO_LARGE, e.reason());
        assertTrue((Boolean) kit.service.status(scope, null).get("tooLarge"));
        InferenceException again = assertThrows(InferenceException.class, () -> kit.askInferred(scope, null, ":d1 a :Person", false));
        assertEquals(InferenceException.TOO_LARGE, again.reason());
    }

    @Test
    void aChangeDuringCaptureIsRetriedOnceThenReported() {
        load(scope);
        AtomicInteger reads = new AtomicInteger();
        kit.source.beforeRead = () -> {
            if (reads.incrementAndGet() == 1) {
                kit.source.bump(scope);
            }
        };
        assertTrue(kit.askInferred(scope, null, ":d1 a :EnvironmentalMeasurementDatum", true));
        kit.service.cache().clear();
        kit.source.beforeRead = () -> kit.source.bump(scope);
        InferenceException e = assertThrows(InferenceException.class, () -> kit.askInferred(scope, null, ":d1 a :Person", true));
        assertEquals(InferenceException.CHANGED, e.reason());
    }

    @Test
    void theSnapshotIsCapturedOnceAndQueriesDoNotReadTheLiveGraph() {
        load(scope);
        kit.askInferred(scope, null, ":d1 a :EnvironmentalMeasurementDatum", true);
        AtomicInteger reads = new AtomicInteger();
        kit.source.beforeRead = reads::incrementAndGet;
        kit.askInferred(scope, null, ":d1 a :EnvironmentalMeasurementDatum", false);
        kit.askInferred(scope, null, ":d1 a :Person", false);
        assertEquals(0, reads.get());
    }

    @Test
    void theCacheKeepsAtMostThreeSnapshotsAndExpiresOldOnes() {
        long[] now = {0};
        InferenceSnapshotCache cache = new InferenceSnapshotCache(3, 1000, 10_000_000, () -> now[0]);
        InferenceService service = new InferenceService(kit.source, null, cache, 10, 200_000, 500_000);
        for (int i = 0; i < 5; i++) {
            SparqlQueryScope s = InferenceFixtures.draftScope("user" + i);
            kit.source.set(s, InferenceFixtures.turtle(DATA, s.graphs().get(0)));
            SparqlUserQueryResult r = service.query(s, "user" + i, InferenceTestKit.P + "ASK { ?s ?p ?o }", true, 10).result();
            assertTrue(((SparqlUserQueryResult.Ask) r).value());
        }
        assertEquals(3, cache.size());
        InferenceException evicted = assertThrows(InferenceException.class, () -> service.query(InferenceFixtures.draftScope("user0"),
                "user0", InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10));
        assertNotEquals(InferenceException.CHANGED, evicted.reason());
        now[0] = 5000;
        assertEquals(0, cache.size());
    }

    @Test
    void theCacheEvictsByTripleCount() {
        InferenceSnapshotCache cache = new InferenceSnapshotCache(3, 100_000, 20, () -> 0L);
        InferenceService service = new InferenceService(kit.source, null, cache, 10, 200_000, 500_000);
        for (int i = 0; i < 2; i++) {
            SparqlQueryScope s = InferenceFixtures.draftScope("big" + i);
            kit.source.set(s, InferenceFixtures.turtle(DATA, s.graphs().get(0)));
            service.query(s, "big" + i, InferenceTestKit.P + "ASK { ?s ?p ?o }", true, 10);
        }
        assertEquals(1, cache.size());
    }
}
