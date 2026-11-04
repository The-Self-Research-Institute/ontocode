package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.owlEditor.service.RuleService.InvalidRuleException;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;
import self.research.ontology.owlEditor.service.RuleService.RuleNameTakenException;
import self.research.ontology.owlEditor.service.RuleService.RuleNotFoundException;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleLifecycleTest {

    static final String MAIN = "urn:graph:main";
    static final String BASE = "urn:graph:base";
    static final String DRAFT = "urn:graph:draft:u1";
    static final String OTHER = "urn:graph:draft:u2";
    static final String FROZEN = "urn:graph:draft:pr-1";
    static final String SWRL_VARIABLE = "http://www.w3.org/2003/11/swrl#Variable";

    record Entry(String graph, ExactDelta delta) {
    }

    final ObjectMapper mapper = new ObjectMapper();
    final List<Entry> entries = new ArrayList<>();
    final List<Entry> undone = new ArrayList<>();
    RuleTestSupport.Store store;
    RuleService service;
    OntologyMergeService merge;

    String graphFor(boolean draft, String user) {
        return draft ? "urn:graph:draft:" + user : MAIN;
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new RuleTestSupport.Store();
        store.put(MAIN, RuleTestSupport.parse(RuleTestSupport.seedTurtle(), RDFFormat.TURTLE));
        store.copy(MAIN, BASE);
        store.copy(MAIN, DRAFT);
        store.copy(MAIN, OTHER);
        SwrlRuleClient client = new SwrlRuleClient((method, url, body, token) -> {
            if (!url.endsWith("/rules/parse")) {
                return new SwrlRuleClient.Answer(200, "[]");
            }
            JsonNode request = mapper.readTree(body);
            String text = request.path("ruleText").asText();
            Map<String, Object> answer = new HashMap<>();
            if (text.startsWith("bad")) {
                answer.put("valid", false);
                answer.put("axiom", null);
                answer.put("errors", List.of("I do not know the word '" + text + "'"));
                answer.put("suggestions", List.of("Try Student>Person"));
            } else {
                String[] parts = text.split(">");
                answer.put("valid", true);
                answer.put("axiom", RuleTestSupport.functionalRule(parts[0].trim(), parts[1].trim()));
                answer.put("errors", List.of());
                answer.put("suggestions", List.of());
            }
            return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(answer));
        }, "http://swrl", "t");
        service = new RuleService(client,
                (projectId, draft, user) -> store.ontology(graphFor(draft, user)),
                (projectId, sparql, draft, user, name, origin) -> {
                    String graph = graphFor(draft, user);
                    DeltaCapture capture = DeltaCapture.beginWholeGraph(store.repo(), graph).orElseThrow();
                    store.update(graph, sparql);
                    ExactDelta delta = capture.finish().orElseThrow();
                    entries.add(new Entry(graph, ExactDelta.of(ExactDelta.write(delta.deleted()), ExactDelta.write(delta.inserted()))));
                    undone.clear();
                });
        merge = new OntologyMergeService(null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    void undo() {
        Entry last = entries.remove(entries.size() - 1);
        store.update(last.graph(), DeltaSparql.reverse(last.delta()));
        undone.add(last);
    }

    void redo() {
        Entry next = undone.remove(undone.size() - 1);
        store.update(next.graph(), DeltaSparql.forward(next.delta()));
        entries.add(next);
    }

    RuleInput input(String name, String text) {
        return new RuleInput(name, text, "a comment", "People", null);
    }

    String createIn(boolean draft, String user, String name, String text) throws Exception {
        return String.valueOf(service.create("p", draft, user, "U", input(name, text), ChangeOrigin.manual()).get("id"));
    }

    Set<SWRLRule> rules(String graph) {
        return RuleTestSupport.rulesOf(store.ontology(graph));
    }

    String nameOf(SWRLRule rule) {
        return SwrlRuleKeys.name(rule).orElse("-");
    }

    boolean isomorphic(Model a, Model b) {
        return Models.isomorphic(a, b);
    }

    @Test
    void ruleCreatedInADraftIsInvisibleToPublicAndToOtherDrafts() throws Exception {
        String id = createIn(true, "u1", "Students are people", "Student>Person");

        assertEquals(1, rules(DRAFT).size());
        assertTrue(SwrlRuleKeys.ruleId(rules(DRAFT).iterator().next()).filter(id::equals).isPresent());
        assertTrue(rules(MAIN).isEmpty());
        assertTrue(rules(OTHER).isEmpty());
    }

    @Test
    void theSavedRuleCarriesNameCommentCategoryAndIdAsAnnotations() throws Exception {
        createIn(false, null, "Students are people", "Student>Person");

        SWRLRule rule = rules(MAIN).iterator().next();
        assertEquals("Students are people", nameOf(rule));
        assertEquals(4, rule.annotationsAsList().size());
        assertFalse(self.research.ontology.common.rules.DisabledRules.isDisabled(rule));
    }

    @Test
    void discardingTheDraftRemovesItsRulesAndLeavesPublicUntouched() throws Exception {
        createIn(true, "u1", "Students are people", "Student>Person");
        createIn(false, null, "Teachers are people", "Teacher>Person");

        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);

        assertEquals(List.of("Teachers are people"), rules(DRAFT).stream().map(this::nameOf).toList());
        assertEquals(List.of("Teachers are people"), rules(MAIN).stream().map(this::nameOf).toList());
    }

    @Test
    void publishingTheDraftCarriesTheRuleToPublicAndAnEditedRuleReplacesTheOldOne() throws Exception {
        String keep = createIn(false, null, "Teachers are people", "Teacher>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        createIn(true, "u1", "Students are people", "Student>Person");
        service.update("p", keep, true, "u1", "U", new RuleInput(null, "Teacher>Parent", null, null, null), ChangeOrigin.manual());

        OWLOntology merged = publish();

        Set<String> names = new HashSet<>();
        merged.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).forEach(r -> names.add(nameOf(r)));
        assertEquals(Set.of("Students are people", "Teachers are people"), names);
        assertEquals(2, RuleTestSupport.rulesOf(merged).size());
    }

    @Test
    void aRuleDeletedInTheDraftIsDeletedOnPublish() throws Exception {
        String id = createIn(false, null, "Teachers are people", "Teacher>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        service.delete("p", id, true, "u1", "U", ChangeOrigin.manual());

        assertTrue(RuleTestSupport.rulesOf(publish()).isEmpty());
    }

    OWLOntology publish() throws Exception {
        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        Set<String> conflicts = conflicts(base, main, draft);
        return merge.mergeDraftPublishThreeWay(base, draft, main, conflicts, new HashMap<>(), merge.header(base));
    }

    Set<String> conflicts(OWLOntology base, OWLOntology main, OWLOntology draft) {
        Set<String> found = new LinkedHashSet<>(merge.collectTouchedIris(base, main));
        found.retainAll(merge.collectTouchedIris(base, draft));
        found.removeIf(iri -> merge.definingAxioms(draft, org.semanticweb.owlapi.model.IRI.create(iri))
                .equals(merge.definingAxioms(main, org.semanticweb.owlapi.model.IRI.create(iri))));
        return found;
    }

    @Test
    void createUndoRedoIsExactInBothDirections() throws Exception {
        Model start = store.read(DRAFT);
        createIn(true, "u1", "Students are people", "Student>Person");
        Model created = store.read(DRAFT);

        undo();
        assertTrue(isomorphic(store.read(DRAFT), start));
        redo();
        assertTrue(isomorphic(store.read(DRAFT), created));
    }

    @Test
    void editIsOneChangeSetAndUndoRestoresTheOldRuleCompletely() throws Exception {
        String id = createIn(true, "u1", "Students are people", "Student>Person");
        Model before = store.read(DRAFT);
        int entriesBefore = entries.size();

        service.update("p", id, true, "u1", "U", new RuleInput("Students are persons", "Student>Parent", null, null, null), ChangeOrigin.manual());

        assertEquals(entriesBefore + 1, entries.size());
        Entry edit = entries.get(entries.size() - 1);
        assertFalse(edit.delta().deleted().isEmpty());
        assertFalse(edit.delta().inserted().isEmpty());
        assertEquals(List.of("Students are persons"), rules(DRAFT).stream().map(this::nameOf).toList());
        Model after = store.read(DRAFT);

        undo();
        assertTrue(isomorphic(store.read(DRAFT), before));
        assertEquals(List.of("Students are people"), rules(DRAFT).stream().map(this::nameOf).toList());
        redo();
        assertTrue(isomorphic(store.read(DRAFT), after));
    }

    @Test
    void togglingARuleOffAddsTheDisabledMarkerAndUndoRemovesIt() throws Exception {
        String id = createIn(true, "u1", "Students are people", "Student>Person");
        Model enabled = store.read(DRAFT);

        service.update("p", id, true, "u1", "U", new RuleInput(null, null, null, null, false), ChangeOrigin.manual());
        SWRLRule off = rules(DRAFT).iterator().next();
        assertTrue(self.research.ontology.common.rules.DisabledRules.isDisabled(off));
        assertEquals("Students are people", nameOf(off));
        assertEquals(id, SwrlRuleKeys.ruleId(off).orElseThrow());

        undo();
        assertTrue(isomorphic(store.read(DRAFT), enabled));
        assertFalse(self.research.ontology.common.rules.DisabledRules.isDisabled(rules(DRAFT).iterator().next()));
    }

    @Test
    void deleteUndoRedoRestoresTheRuleExactly() throws Exception {
        String id = createIn(true, "u1", "Students are people", "Student>Person");
        createIn(true, "u1", "Teachers are people", "Teacher>Person");
        Model both = store.read(DRAFT);

        service.delete("p", id, true, "u1", "U", ChangeOrigin.manual());
        assertEquals(List.of("Teachers are people"), rules(DRAFT).stream().map(this::nameOf).toList());
        Model afterDelete = store.read(DRAFT);

        undo();
        assertTrue(isomorphic(store.read(DRAFT), both));
        redo();
        assertTrue(isomorphic(store.read(DRAFT), afterDelete));
    }

    @Test
    void deletingARuleLeavesTheOntologyEqualToTheOriginalDespiteOrphanVariables() throws Exception {
        OWLOntology original = store.ontology(DRAFT);
        String id = createIn(true, "u1", "Students are people", "Student>Person");

        service.delete("p", id, true, "u1", "U", ChangeOrigin.manual());

        OWLOntology after = store.ontology(DRAFT);
        assertEquals(original.getLogicalAxioms(), after.getLogicalAxioms());
        assertEquals(original.getClassesInSignature(), after.getClassesInSignature());
        assertTrue(RuleTestSupport.rulesOf(after).isEmpty());
        long orphans = store.read(DRAFT).filter(null, RDF.TYPE, org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance().createIRI(SWRL_VARIABLE)).size();
        assertTrue(orphans <= 1);
        createIn(true, "u1", "Students are people", "Student>Person");
        assertEquals(1, rules(DRAFT).size());
    }

    @Test
    void aDuplicateNameInTheSameGraphIsRefusedButTheSameNameInAnotherGraphIsFine() throws Exception {
        createIn(true, "u1", "Students are people", "Student>Person");

        assertThrows(RuleNameTakenException.class, () -> createIn(true, "u1", "  students ARE people ", "Teacher>Person"));
        createIn(false, null, "Students are people", "Student>Person");
        createIn(true, "u2", "Students are people", "Student>Person");
        assertEquals(1, rules(DRAFT).size());
    }

    @Test
    void renamingToAnotherRulesNameIsRefused() throws Exception {
        createIn(true, "u1", "One", "Student>Person");
        String second = createIn(true, "u1", "Two", "Teacher>Person");

        assertThrows(RuleNameTakenException.class, () ->
                service.update("p", second, true, "u1", "U", new RuleInput("One", null, null, null, null), ChangeOrigin.manual()));
    }

    @Test
    void anInvalidRuleIsRefusedWithTheFriendlyErrorsAndNothingIsWritten() throws Exception {
        Model before = store.read(DRAFT);

        InvalidRuleException e = assertThrows(InvalidRuleException.class, () -> createIn(true, "u1", "Broken", "bad rule"));

        assertEquals(1, e.errors().size());
        assertEquals(List.of("Try Student>Person"), e.suggestions());
        assertTrue(isomorphic(store.read(DRAFT), before));
        assertTrue(entries.isEmpty());
    }

    @Test
    void updatingOrDeletingAnUnknownRuleIsNotFound() {
        assertThrows(RuleNotFoundException.class, () ->
                service.update("p", "nope", true, "u1", "U", new RuleInput("x", null, null, null, null), ChangeOrigin.manual()));
        assertThrows(RuleNotFoundException.class, () -> service.delete("p", "nope", true, "u1", "U", ChangeOrigin.manual()));
    }

    @Test
    void theFrozenPullRequestCopyHoldsTheRuleAndLaterDraftEditsDoNotChangeIt() throws Exception {
        String id = createIn(true, "u1", "Students are people", "Student>Person");

        store.copy(DRAFT, FROZEN);
        assertEquals(List.of("Students are people"), rules(FROZEN).stream().map(this::nameOf).toList());
        assertTrue(isomorphic(store.read(FROZEN), store.read(DRAFT)));

        service.update("p", id, true, "u1", "U", new RuleInput("Renamed", "Teacher>Person", null, null, null), ChangeOrigin.manual());
        service.delete("p", id, true, "u1", "U", ChangeOrigin.manual());

        assertTrue(rules(DRAFT).isEmpty());
        assertEquals(List.of("Students are people"), rules(FROZEN).stream().map(this::nameOf).toList());
    }

    @Test
    void twoSidesEditingTheSameRuleConflictAndTheChosenVersionReplacesTheOtherWithoutDuplicates() throws Exception {
        String id = createIn(false, null, "Students are people", "Student>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        service.update("p", id, false, null, "U", new RuleInput(null, "Student>Parent", null, null, null), ChangeOrigin.manual());
        service.update("p", id, true, "u1", "U", new RuleInput(null, "Student>Agent", null, null, null), ChangeOrigin.manual());

        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));
        Set<String> conflicts = conflicts(base, main, draft);
        assertEquals(Set.of(SwrlRuleKeys.keyOf(id)), conflicts);

        OWLOntology merged = merge.mergeDraftPublishThreeWay(base, draft, main, conflicts, new HashMap<>(), merge.header(base));
        assertEquals(1, RuleTestSupport.rulesOf(merged).size());
    }

    @Test
    void renamingOnOneSideAndEditingTextOnTheOtherStillConflictsOnTheSameRule() throws Exception {
        String id = createIn(false, null, "Students are people", "Student>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        service.update("p", id, false, null, "U", new RuleInput("Pupils are people", null, null, null, null), ChangeOrigin.manual());
        service.update("p", id, true, "u1", "U", new RuleInput(null, "Student>Parent", null, null, null), ChangeOrigin.manual());

        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));

        assertEquals(Set.of(SwrlRuleKeys.keyOf(id)), conflicts(base, main, draft));
    }

    @Test
    void editingOneSideAndDeletingOnTheOtherIsAConflict() throws Exception {
        String id = createIn(false, null, "Students are people", "Student>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        service.delete("p", id, false, null, "U", ChangeOrigin.manual());
        service.update("p", id, true, "u1", "U", new RuleInput(null, "Student>Parent", null, null, null), ChangeOrigin.manual());

        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));

        assertEquals(Set.of(SwrlRuleKeys.keyOf(id)), conflicts(base, main, draft));
    }

    @Test
    void differentRulesEditedOnEachSideMergeCleanlyWithBothKept() throws Exception {
        String a = createIn(false, null, "Rule A", "Student>Person");
        String b = createIn(false, null, "Rule B", "Teacher>Person");
        store.clear(BASE);
        store.copy(MAIN, BASE);
        store.clear(DRAFT);
        store.copy(MAIN, DRAFT);
        service.update("p", a, false, null, "U", new RuleInput(null, "Student>Parent", null, null, null), ChangeOrigin.manual());
        service.update("p", b, true, "u1", "U", new RuleInput(null, "Teacher>Agent", null, null, null), ChangeOrigin.manual());

        OWLOntology base = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(BASE)));
        OWLOntology main = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(MAIN)));
        OWLOntology draft = merge.loadOntologyFromRdf(RuleTestSupport.toRdfXml(store.read(DRAFT)));
        assertTrue(conflicts(base, main, draft).isEmpty());

        OWLOntology merged = merge.mergeDraftPublishThreeWay(base, draft, main, new HashSet<>(), new HashMap<>(), merge.header(base));
        Map<String, String> bodies = new HashMap<>();
        for (SWRLRule rule : RuleTestSupport.rulesOf(merged)) {
            bodies.put(nameOf(rule), rule.head().findFirst().orElseThrow().toString());
        }
        assertEquals(2, bodies.size());
        assertTrue(bodies.get("Rule A").contains("Parent"));
        assertTrue(bodies.get("Rule B").contains("Agent"));
    }

    @Test
    void rulesWithoutAnIdStillTravelThroughTheOtherAxiomsSection() throws Exception {
        SWRLRule plain = RuleTestSupport.plainRule("Student", "Person");

        assertTrue(OntologyHeaderMerge.isOtherAxiom(plain));
        assertFalse(OntologyHeaderMerge.isOtherAxiom(rules(publishedWith("Students are people")).iterator().next()));
    }

    String publishedWith(String name) throws Exception {
        createIn(false, null, name, "Student>Person");
        return MAIN;
    }
}
