package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceEqualityTest {

    static final String BASE = "<http://e/DrSmith> owl:sameAs <http://e/JohnSmith> . "
            + ":DrSmith :worksAt :HospitalA . :Alice :knows :DrSmith . :DrSmith a :Doctor . "
            + ":Doctor rdfs:subClassOf :Clinician . :Clinician rdfs:subClassOf :Person . "
            + ":worksAt a owl:ObjectProperty . :knows a owl:ObjectProperty . :Doctor a owl:Class . :Clinician a owl:Class . :Person a owl:Class . "
            + ":DrSmith a owl:NamedIndividual . :JohnSmith a owl:NamedIndividual . :Alice a owl:NamedIndividual . :HospitalA a owl:NamedIndividual . ";

    InferenceTestKit kit;
    SparqlQueryScope scope;

    @BeforeEach
    void setUp() {
        kit = new InferenceTestKit();
        scope = InferenceFixtures.publicScope();
        kit.source.set(scope, InferenceFixtures.turtle(BASE, scope.graphs().get(0)));
    }

    @Test
    void jonSmithInheritsWorksAtOnlyWhenInferredIncluded() {
        assertFalse(kit.askAsserted(scope, ":JohnSmith :worksAt :HospitalA"));
        assertTrue(kit.askInferred(scope, null, ":JohnSmith :worksAt :HospitalA", true));
        assertTrue(kit.askInferred(scope, null, ":DrSmith :worksAt :HospitalA", false));
    }

    @Test
    void objectPositionIsExpandedToo() {
        assertFalse(kit.askAsserted(scope, ":Alice :knows :JohnSmith"));
        assertTrue(kit.askInferred(scope, null, ":Alice :knows :JohnSmith", true));
        assertTrue(kit.askInferred(scope, null, ":Alice :knows :DrSmith", false));
    }

    @Test
    void classMembershipsAreMaterializedUnderBothNamesWithTheSubclassChain() {
        for (String who : List.of(":DrSmith", ":JohnSmith")) {
            for (String cls : List.of(":Doctor", ":Clinician", ":Person")) {
                assertTrue(kit.askInferred(scope, null, who + " a " + cls, true), who + " a " + cls);
            }
        }
        assertFalse(kit.askAsserted(scope, ":JohnSmith a :Doctor"));
    }

    @Test
    void joinsUsingEitherNameGiveTheSameRows() {
        List<List<String>> viaDr = kit.rows(scope, null, "SELECT ?who ?h WHERE { ?who :knows :DrSmith . :DrSmith :worksAt ?h }", "who", "h");
        List<List<String>> viaJohn = kit.rows(scope, null, "SELECT ?who ?h WHERE { ?who :knows :JohnSmith . :JohnSmith :worksAt ?h }", "who", "h");
        assertEquals(List.of(List.of("http://e/Alice", "http://e/HospitalA")), viaDr);
        assertEquals(viaDr, viaJohn);
    }

    @Test
    void sameAsIsInTheViewAsAllOrderedPairsWithoutReflexiveTriples() {
        assertTrue(kit.askInferred(scope, null, ":JohnSmith owl:sameAs :DrSmith", true));
        assertFalse(kit.askInferred(scope, null, ":DrSmith owl:sameAs :DrSmith", false));
        assertFalse(kit.askInferred(scope, null, ":JohnSmith owl:sameAs :JohnSmith", false));
    }

    @Test
    void originalsAndAssertedFactsStayUnderTheirOwnNames() {
        assertTrue(kit.askInferred(scope, null, ":DrSmith :worksAt :HospitalA . :DrSmith owl:sameAs :JohnSmith . :Alice :knows :DrSmith", true));
        InferenceService.Answer answer = kit.service.query(scope, null,
                InferenceTestKit.P + "ASK { :DrSmith a :Doctor }", false, 10);
        assertTrue(((SparqlUserQueryResult.Ask) answer.result()).value());
    }

    @Test
    void removingTheSameAsPremiseRetractsDependentConclusionsAndUndoIsANewKey() {
        kit.askInferred(scope, null, ":Alice :knows :JohnSmith", true);
        String firstId = (String) kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");

        List<Statement> without = InferenceFixtures.turtle(BASE.replace("<http://e/DrSmith> owl:sameAs <http://e/JohnSmith> . ", ""),
                scope.graphs().get(0));
        kit.source.set(scope, without);
        InferenceException stale = assertThrows(InferenceException.class,
                () -> kit.askInferred(scope, null, ":Alice :knows :JohnSmith", false));
        assertEquals(InferenceException.STALE, stale.reason());
        assertFalse(kit.askInferred(scope, null, ":Alice :knows :JohnSmith", true));
        assertFalse(kit.askInferred(scope, null, ":JohnSmith :worksAt :HospitalA", false));
        assertFalse(kit.askInferred(scope, null, ":JohnSmith a :Doctor", false));
        List<List<String>> leftovers = kit.rows(scope, null,
                "SELECT ?s ?p ?o WHERE { ?s ?p ?o . FILTER(?s = :JohnSmith || ?o = :JohnSmith) }", "s", "p", "o");
        assertTrue(leftovers.stream().allMatch(r -> r.get(1).endsWith("#type")), leftovers.toString());
        assertTrue(leftovers.stream().noneMatch(r -> r.get(1).contains("sameAs") || r.get(1).contains("worksAt")), leftovers.toString());

        kit.source.set(scope, InferenceFixtures.turtle(BASE, scope.graphs().get(0)));
        InferenceException afterUndo = assertThrows(InferenceException.class,
                () -> kit.askInferred(scope, null, ":Alice :knows :JohnSmith", false));
        assertEquals(InferenceException.STALE, afterUndo.reason());
        kit.askInferred(scope, null, ":Alice :knows :JohnSmith", true);
        String thirdId = (String) kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", false, 10).inferred().get("snapshotId");
        assertNotEquals(firstId, thirdId);
    }

    @Test
    void sameAsPlusDifferentFromOnTheSamePairIsInconsistent() {
        kit.source.set(scope, InferenceFixtures.turtle(BASE + ":DrSmith owl:differentFrom :JohnSmith . ", scope.graphs().get(0)));
        InconsistentOntologyException e = assertThrows(InconsistentOntologyException.class,
                () -> kit.askInferred(scope, null, ":Alice :knows :JohnSmith", true));
        assertTrue(e.explanation().contains("http://e/DrSmith") && e.explanation().contains("http://e/JohnSmith"), e.explanation());
        assertThrows(InconsistentOntologyException.class, () -> kit.askInferred(scope, null, "?s ?p ?o", false));
    }

    @Test
    void differentFromIsNeverInferredFromDifferentIris() {
        assertFalse(kit.askInferred(scope, null, ":Alice owl:differentFrom :HospitalA", true));
        assertFalse(kit.askInferred(scope, null, "?a owl:differentFrom ?b", false));
    }

    @Test
    void entailedDifferentFromFollowsTheEqualityGroup() {
        kit.source.set(scope, InferenceFixtures.turtle(BASE + ":DrSmith owl:differentFrom :Alice . ", scope.graphs().get(0)));
        assertTrue(kit.askInferred(scope, null, ":JohnSmith owl:differentFrom :Alice", true));
        assertTrue(kit.askInferred(scope, null, ":DrSmith owl:differentFrom :Alice", false));
    }

    @Test
    void groupSizeLimitMarksTheRunIncompleteWithAReason() {
        kit = new InferenceTestKit(2, 200_000, 500_000);
        kit.source.set(scope, InferenceFixtures.turtle(BASE + ":JohnSmith owl:sameAs :JSmith . ", scope.graphs().get(0)));
        InferenceService.Answer answer = kit.service.query(scope, null, InferenceTestKit.P + "ASK { :JSmith :worksAt :HospitalA }", true, 10);
        assertFalse(((SparqlUserQueryResult.Ask) answer.result()).value());
        assertEquals(true, answer.inferred().get("partial"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> equality = (java.util.Map<String, Object>) answer.inferred().get("equality");
        assertEquals(true, equality.get("incomplete"));
        assertEquals(3, equality.get("largestGroup"));
        assertTrue(((List<?>) answer.inferred().get("partialReasons")).stream().anyMatch(r -> r.toString().contains("larger than 2")));
    }

    @Test
    void equalityTripleCapMarksTheRunIncomplete() {
        kit = new InferenceTestKit(10, 2, 500_000);
        kit.source.set(scope, InferenceFixtures.turtle(BASE, scope.graphs().get(0)));
        InferenceService.Answer answer = kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", true, 10);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> equality = (java.util.Map<String, Object>) answer.inferred().get("equality");
        assertEquals(true, equality.get("incomplete"));
        assertEquals(2L, equality.get("triplesAdded"));
        assertEquals(true, answer.inferred().get("partial"));
    }

    @Test
    void metricsAreReportedForACompleteRun() {
        InferenceService.Answer answer = kit.service.query(scope, null, InferenceTestKit.P + "ASK { ?s ?p ?o }", true, 10);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> equality = (java.util.Map<String, Object>) answer.inferred().get("equality");
        assertEquals(1, equality.get("groups"));
        assertEquals(2, equality.get("largestGroup"));
        assertEquals(false, equality.get("incomplete"));
        assertTrue((Long) equality.get("triplesAdded") > 0);
        assertEquals(false, answer.inferred().get("partial"));
    }
}
