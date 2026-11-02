package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.owlEditor.service.RuleImportSupport.file;
import static self.research.ontology.owlEditor.service.RuleImportSupport.row;

class RuleImportNameIndependenceTest {

    static final String NS = RuleTestSupport.NS;
    static final List<String> TEXTS = List.of("Student>Person", "Person>Agent", "Teacher>Person", "Child>Person", "demo 2");
    static final String BASE = "INSERT DATA { "
            + "<" + NS + "Child> a <http://www.w3.org/2002/07/owl#Class> . "
            + "<" + NS + "hasParent> a <http://www.w3.org/2002/07/owl#ObjectProperty> . "
            + "<" + NS + "hasGrandparent> a <http://www.w3.org/2002/07/owl#ObjectProperty> . "
            + "<" + NS + "alice> a <" + NS + "Student> , <http://www.w3.org/2002/07/owl#NamedIndividual> . "
            + "<" + NS + "bob> a <" + NS + "Teacher> , <http://www.w3.org/2002/07/owl#NamedIndividual> . "
            + "<" + NS + "carol> a <" + NS + "Child> , <http://www.w3.org/2002/07/owl#NamedIndividual> . "
            + "<" + NS + "dan> <" + NS + "hasParent> <" + NS + "erin> ; a <http://www.w3.org/2002/07/owl#NamedIndividual> . "
            + "<" + NS + "erin> <" + NS + "hasParent> <" + NS + "fay> ; a <http://www.w3.org/2002/07/owl#NamedIndividual> . "
            + "<" + NS + "fay> a <http://www.w3.org/2002/07/owl#NamedIndividual> . }";

    final List<RuleHistoryHarness> open = new ArrayList<>();
    final ReasonerService reasoner = new ReasonerService();

    RuleImportNameIndependenceTest() {
        ReflectionTestUtils.setField(reasoner, "reasonerOperationTimeoutMs", 60_000L);
    }

    @AfterEach
    void tearDown() {
        reasoner.clearCache();
        open.forEach(RuleHistoryHarness::close);
    }

    RuleHistoryHarness harness() throws Exception {
        RuleHistoryHarness h = new RuleHistoryHarness(x -> RuleImportSupport.client());
        open.add(h);
        h.store.update(RuleHistoryHarness.MAIN, BASE);
        return h;
    }

    RuleImportReport importInto(RuleHistoryHarness h, List<String> names, boolean reverse) throws Exception {
        List<RuleImportFile.Row> rows = new ArrayList<>();
        for (int i = 0; i < TEXTS.size(); i++) {
            rows.add(row(i + 2, names.get(i), TEXTS.get(i)));
        }
        if (reverse) {
            Collections.reverse(rows);
        }
        return h.rules.importRules(RuleHistoryHarness.P, false, RuleHistoryHarness.OWNER, "T",
                file("r.xlsx", rows.toArray(new RuleImportFile.Row[0])), UUID.randomUUID().toString(), null, false,
                ChangeOrigin.manual(), () -> null);
    }

    Set<String> axioms(RuleHistoryHarness h) {
        return h.rulesIn(false).stream().map(r -> r.getAxiomWithoutAnnotations().toString()).collect(Collectors.toCollection(TreeSet::new));
    }

    Set<String> inferred(RuleHistoryHarness h) {
        OWLOntology ontology = h.store.ontology(RuleHistoryHarness.MAIN);
        try (ReasonerLease lease = reasoner.lease(ontology, ReasonerType.HERMIT)) {
            OWLReasoner hermit = lease.reasoner();
            Set<String> results = new TreeSet<>();
            for (String who : List.of("alice", "bob", "carol", "dan", "erin", "fay")) {
                OWLNamedIndividual individual = OWLManager.getOWLDataFactory().getOWLNamedIndividual(IRI.create(NS + who));
                for (OWLClass type : hermit.getTypes(individual, false).entities().toList()) {
                    if (!type.isOWLThing()) {
                        results.add(who + " is " + type.getIRI().getShortForm());
                    }
                }
                hermit.getObjectPropertyValues(individual, OWLManager.getOWLDataFactory()
                        .getOWLObjectProperty(IRI.create(NS + "hasGrandparent"))).entities()
                        .forEach(value -> results.add(who + " has grandparent " + value.getIRI().getShortForm()));
            }
            return results;
        }
    }

    @Test
    void theSameRulesGiveTheSameAxiomsAndTheSameInferencesWhateverTheirNamesOrderOrNeighbours() throws Exception {
        RuleHistoryHarness plain = harness();
        importInto(plain, List.of("Students are people", "People are agents", "Teachers are people", "Child age", "Grandparents"), false);

        RuleHistoryHarness renamedAndReversed = harness();
        importInto(renamedAndReversed, List.of("Child", "Person", "Student", "Zeta", "agent"), true);

        RuleHistoryHarness withNeighbour = harness();
        withNeighbour.create(false, "Existing", "Parent>Londoner");
        importInto(withNeighbour, List.of("Alpha", "Beta", "Gamma", "Child age", "Delta"), false);

        Set<String> expectedAxioms = axioms(plain);
        assertEquals(5, expectedAxioms.size());
        assertEquals(expectedAxioms, axioms(renamedAndReversed));
        Set<String> withoutNeighbour = axioms(withNeighbour);
        withoutNeighbour.remove(RuleTestSupport.plainRule("Parent", "Londoner").toString());
        assertEquals(expectedAxioms, withoutNeighbour);

        Set<String> expected = inferred(plain);
        assertTrue(expected.contains("alice is Person"), expected.toString());
        assertTrue(expected.contains("alice is Agent"), expected.toString());
        assertTrue(expected.contains("carol is Person"), expected.toString());
        assertTrue(expected.contains("dan has grandparent fay"), expected.toString());
        assertEquals(expected, inferred(renamedAndReversed));
        assertEquals(expected, inferred(withNeighbour));
    }

    @Test
    void aRuleNamedLikeAnExistingClassChangesNothingAboutWhatItDoes() throws Exception {
        RuleHistoryHarness named = harness();
        RuleHistoryHarness other = harness();
        named.rules.importRules(RuleHistoryHarness.P, false, RuleHistoryHarness.OWNER, "T",
                file("r.xlsx", row(2, "Child age", "Child>Person"), row(3, "Child", "Student>Person")),
                UUID.randomUUID().toString(), null, false, ChangeOrigin.manual(), () -> null);
        other.rules.importRules(RuleHistoryHarness.P, false, RuleHistoryHarness.OWNER, "T",
                file("r.xlsx", row(2, "Some rule", "Child>Person"), row(3, "Another rule", "Student>Person")),
                UUID.randomUUID().toString(), null, false, ChangeOrigin.manual(), () -> null);

        assertEquals(axioms(other), axioms(named));
        assertEquals(inferred(other), inferred(named));
        assertTrue(inferred(named).contains("carol is Person"));
    }

    @Test
    void theTwelveDemoRulesGiveTheSameAxiomsUnderOtherNamesAndOrdersAndNextToOtherRules() throws Exception {
        RuleHistoryHarness plain = harness();
        plain.rules.importRules(RuleHistoryHarness.P, false, RuleHistoryHarness.OWNER, "T", RuleImportSupport.demoFile("a.xlsx"),
                UUID.randomUUID().toString(), null, false, ChangeOrigin.manual(), () -> null);

        List<RuleImportFile.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(row(i + 1, i == 1 ? "Child age" : i == 2 ? "Child" : "Z" + (13 - i), "demo " + i, true));
        }
        Collections.reverse(rows);
        RuleHistoryHarness shuffled = harness();
        shuffled.create(false, "Existing", "Parent>Londoner");
        shuffled.rules.importRules(RuleHistoryHarness.P, false, RuleHistoryHarness.OWNER, "T",
                file("b.xlsx", rows.toArray(new RuleImportFile.Row[0])), UUID.randomUUID().toString(), null, false,
                ChangeOrigin.manual(), () -> null);

        Set<String> expected = axioms(plain);
        Set<String> got = axioms(shuffled);
        got.remove(RuleTestSupport.plainRule("Parent", "Londoner").toString());
        assertEquals(12, expected.size());
        assertEquals(expected, got);
    }
}
