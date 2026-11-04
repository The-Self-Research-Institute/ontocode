package self.research.ontology.owlEditor.util;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import self.research.ontology.owlEditor.service.RuleTestSupport;
import self.research.ontology.owlEditor.service.delta.DeltaCapture;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OwlAxiomSparqlWriterRuleTest {

    static final String NS = RuleTestSupport.NS;
    static final String OC = "http://ontocode.org/ns#";
    static final String SWRL = "http://www.w3.org/2003/11/swrl#";
    static final String SWRLB = "http://www.w3.org/2003/11/swrlb#";
    static final String GRAPH = "urn:graph:main";

    final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    final OWLDataFactory df = manager.getOWLDataFactory();
    RuleTestSupport.Store store;

    static org.semanticweb.owlapi.model.IRI oi(String s) {
        return org.semanticweb.owlapi.model.IRI.create(s);
    }

    OWLClass cls(String n) {
        return df.getOWLClass(oi(NS + n));
    }

    OWLObjectProperty op(String n) {
        return df.getOWLObjectProperty(oi(NS + n));
    }

    OWLDataProperty dp(String n) {
        return df.getOWLDataProperty(oi(NS + n));
    }

    SWRLVariable var(String n) {
        return df.getSWRLVariable(oi(NS + "var/" + n));
    }

    OWLNamedIndividual ind(String n) {
        return df.getOWLNamedIndividual(oi(NS + n));
    }

    Set<OWLAnnotation> notes(String ruleId, String label, String comment, boolean enabled) {
        Set<OWLAnnotation> set = new LinkedHashSet<>();
        set.add(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral(label)));
        if (comment != null) {
            set.add(df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral(comment)));
        }
        set.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(oi(OC + "ruleId")), df.getOWLLiteral(ruleId)));
        if (!enabled) {
            set.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(oi(OC + "enabled")), df.getOWLLiteral(false)));
        }
        return set;
    }

    SWRLRule rule(Collection<SWRLAtom> body, Collection<SWRLAtom> head, Set<OWLAnnotation> annotations) {
        return df.getSWRLRule(new LinkedHashSet<>(body), new LinkedHashSet<>(head), annotations);
    }

    SWRLAtom builtin(String name, SWRLDArgument... args) {
        return df.getSWRLBuiltInAtom(oi(SWRLB + name), List.of(args));
    }

    public List<SWRLRule> demoRules() {
        List<SWRLRule> rules = new ArrayList<>();
        rules.add(rule(List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                List.of(df.getSWRLClassAtom(cls("Agent"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLObjectPropertyAtom(op("hasParent"), var("x"), var("y")),
                        df.getSWRLObjectPropertyAtom(op("hasParent"), var("y"), var("z"))),
                List.of(df.getSWRLObjectPropertyAtom(op("hasGrandparent"), var("x"), var("z"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLDataPropertyAtom(dp("hasAge"), var("x"), var("a")),
                        builtin("greaterThan", var("a"), df.getSWRLLiteralArgument(df.getOWLLiteral(17)))),
                List.of(df.getSWRLClassAtom(cls("Adult"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLDataPropertyAtom(dp("hasPrice"), var("x"), var("p")),
                        df.getSWRLDataPropertyAtom(dp("hasTax"), var("x"), var("t")),
                        builtin("add", var("total"), var("p"), var("t"))),
                List.of(df.getSWRLDataPropertyAtom(dp("hasTotal"), var("x"), var("total"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLObjectPropertyAtom(op("hasParent"), var("x"), var("a")),
                        df.getSWRLObjectPropertyAtom(op("hasParent"), var("x"), var("b")),
                        df.getSWRLDifferentIndividualsAtom(var("a"), var("b"))),
                List.of(df.getSWRLClassAtom(cls("HasTwoParents"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLSameIndividualAtom(var("x"), var("y")), df.getSWRLClassAtom(cls("Person"), var("x"))),
                List.of(df.getSWRLClassAtom(cls("Person"), var("y"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLClassAtom(cls("Person"), var("x")),
                        df.getSWRLObjectPropertyAtom(op("livesIn"), var("x"), df.getSWRLIndividualArgument(ind("London")))),
                List.of(df.getSWRLClassAtom(cls("Londoner"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLDataPropertyAtom(dp("hasStatus"), var("x"), df.getSWRLLiteralArgument(df.getOWLLiteral("active")))),
                List.of(df.getSWRLClassAtom(cls("Active"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLClassAtom(df.getOWLObjectSomeValuesFrom(op("hasChild"), cls("Person")), var("x"))),
                List.of(df.getSWRLClassAtom(cls("Parent"), var("x")), df.getSWRLClassAtom(cls("Agent"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLDataPropertyAtom(dp("hasAge"), var("x"), var("a")),
                        df.getSWRLDataRangeAtom(df.getIntegerOWLDatatype(), var("a"))),
                List.of(df.getSWRLClassAtom(cls("HasIntAge"), var("x"))), Set.of()));
        rules.add(rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))),
                List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                notes("rule-1", "Students are people", "Every student is a person", true)));
        rules.add(rule(List.of(df.getSWRLClassAtom(cls("Teacher"), var("x"))),
                List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                notes("rule-2", "Teachers are people", "disabled for now", false)));
        return rules;
    }

    OWLOntology ontologyWith(Collection<SWRLRule> rules) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(oi(RuleTestSupport.ONTO));
        for (String c : RuleTestSupport.CLASSES) {
            m.addAxiom(o, f.getOWLDeclarationAxiom(f.getOWLClass(oi(NS + c))));
        }
        m.addAxiom(o, f.getOWLObjectPropertyDomainAxiom(f.getOWLObjectProperty(oi(NS + "hasParent")), f.getOWLClass(oi(NS + "Person"))));
        for (SWRLRule r : rules) {
            m.addAxiom(o, r);
        }
        return o;
    }

    String save(OWLOntology o, OWLDocumentFormat format) throws Exception {
        StringDocumentTarget target = new StringDocumentTarget();
        o.getOWLOntologyManager().saveOntology(o, format, target);
        return target.toString();
    }

    List<Resource> imps(Model m) {
        return new ArrayList<>(m.filter(null, RDF.TYPE, SimpleValueFactory.getInstance().createIRI(SWRL + "Imp")).subjects());
    }

    @BeforeEach
    void setUp() {
        store = new RuleTestSupport.Store();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    void seed(Collection<SWRLRule> rules) throws Exception {
        store.put(GRAPH, RuleTestSupport.parse(save(ontologyWith(rules), new TurtleDocumentFormat()), RDFFormat.TURTLE));
    }

    @Test
    void roundTripThroughTurtleAndRdfXmlKeepsEveryRuleAndItsAnnotations() throws Exception {
        List<SWRLRule> rules = demoRules();
        OWLOntology o = ontologyWith(rules);
        for (OWLDocumentFormat f : List.of(new TurtleDocumentFormat(), new RDFXMLDocumentFormat())) {
            String text = save(o, f);
            Model m = RuleTestSupport.parse(text, f instanceof TurtleDocumentFormat ? RDFFormat.TURTLE : RDFFormat.RDFXML);
            assertEquals(12, imps(m).size());
            assertEquals(new HashSet<>(rules), RuleTestSupport.rulesOf(RuleTestSupport.load(text)));
        }
    }

    @Test
    void insertThenDeleteByTheWriterRemovesExactlyThatRuleAndNothingElse() throws Exception {
        List<SWRLRule> rules = demoRules();
        seed(List.of());
        for (SWRLRule r : rules) {
            store.update(GRAPH, OwlAxiomSparqlWriter.toInsertData(Set.of(r)));
        }
        assertEquals(12, imps(store.read(GRAPH)).size());
        assertEquals(new HashSet<>(rules), RuleTestSupport.rulesOf(store.ontology(GRAPH)));

        for (SWRLRule victim : rules) {
            Model pre = store.read(GRAPH);
            store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(victim)));
            Set<SWRLRule> left = RuleTestSupport.rulesOf(store.ontology(GRAPH));
            Set<SWRLRule> expected = new HashSet<>(rules);
            expected.remove(victim);
            assertEquals(expected, left, "deleting " + victim);
            assertEquals(imps(pre).size() - 1, imps(store.read(GRAPH)).size());
            store.update(GRAPH, OwlAxiomSparqlWriter.toInsertData(Set.of(victim)));
        }
        assertEquals(new HashSet<>(rules), RuleTestSupport.rulesOf(store.ontology(GRAPH)));
    }

    @Test
    void deletingAPlainRuleDoesNotDamageAnAnnotatedLookAlike() throws Exception {
        SWRLRule plain = rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))), List.of(df.getSWRLClassAtom(cls("Person"), var("x"))), Set.of());
        SWRLRule noted = rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))), List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                notes("twin", "Twin", null, true));
        seed(List.of(plain, noted));

        store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(plain)));

        assertEquals(Set.of(noted), RuleTestSupport.rulesOf(store.ontology(GRAPH)));
    }

    @Test
    void deletingAnAnnotatedRuleLeavesAPlainLookAlikeAndAnotherIdAlone() throws Exception {
        SWRLRule plain = rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))), List.of(df.getSWRLClassAtom(cls("Person"), var("x"))), Set.of());
        SWRLRule one = rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))), List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                notes("one", "One", null, true));
        SWRLRule two = rule(List.of(df.getSWRLClassAtom(cls("Student"), var("x"))), List.of(df.getSWRLClassAtom(cls("Person"), var("x"))),
                notes("two", "Two", null, true));
        seed(List.of(plain, one, two));

        store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(one)));

        assertEquals(Set.of(plain, two), RuleTestSupport.rulesOf(store.ontology(GRAPH)));
    }

    @Test
    void deletingARuleNeverRemovesSharedVariableOrPropertyTypingTriples() throws Exception {
        SWRLRule a = demoRules().get(1);
        SWRLRule b = demoRules().get(4);
        seed(List.of(a, b));
        Model before = store.read(GRAPH);
        long typed = before.filter(null, RDF.TYPE, null).stream().filter(s -> !(s.getSubject() instanceof BNode)).count();

        store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(a)));

        Model after = store.read(GRAPH);
        long typedAfter = after.filter(null, RDF.TYPE, null).stream().filter(s -> !(s.getSubject() instanceof BNode)).count();
        assertEquals(typed, typedAfter);
        assertTrue(after.contains(SimpleValueFactory.getInstance().createIRI(NS + "var/x"), RDF.TYPE,
                SimpleValueFactory.getInstance().createIRI(SWRL + "Variable")));
        assertTrue(after.contains(SimpleValueFactory.getInstance().createIRI(NS + "hasParent"), RDF.TYPE, OWL.OBJECTPROPERTY));
        assertEquals(Set.of(b), RuleTestSupport.rulesOf(store.ontology(GRAPH)));
    }

    @Test
    void deleteDataIsRefusedForRulesBecauseTheyContainBlankNodes() {
        assertThrows(IllegalArgumentException.class, () -> OwlAxiomSparqlWriter.toDeleteData(Set.of(demoRules().get(0))));
    }

    @Test
    void ruleAddAndDeleteAreExactlyReversibleThroughTheWholeGraphCapture() throws Exception {
        List<SWRLRule> rules = demoRules();
        seed(List.of());
        for (SWRLRule victim : rules) {
            Model start = store.read(GRAPH);
            DeltaCapture capture = DeltaCapture.beginWholeGraph(store.repo(), GRAPH).orElseThrow();
            store.update(GRAPH, OwlAxiomSparqlWriter.toInsertData(Set.of(victim)));
            ExactDelta delta = capture.finish().orElseThrow();
            ExactDelta stored = ExactDelta.of(ExactDelta.write(delta.deleted()), ExactDelta.write(delta.inserted()));
            Model afterAdd = store.read(GRAPH);
            store.update(GRAPH, DeltaSparql.reverse(stored));
            assertTrue(Models.isomorphic(store.read(GRAPH), start), "undo of add left residue for " + victim);
            store.update(GRAPH, DeltaSparql.forward(stored));
            assertTrue(Models.isomorphic(store.read(GRAPH), afterAdd));
            store.update(GRAPH, DeltaSparql.reverse(stored));
        }
        seed(rules);
        for (SWRLRule victim : rules) {
            Model full = store.read(GRAPH);
            DeltaCapture capture = DeltaCapture.beginWholeGraph(store.repo(), GRAPH).orElseThrow();
            store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(victim)));
            ExactDelta delta = capture.finish().orElseThrow();
            ExactDelta stored = ExactDelta.of(ExactDelta.write(delta.deleted()), ExactDelta.write(delta.inserted()));
            store.update(GRAPH, DeltaSparql.reverse(stored));
            assertTrue(Models.isomorphic(store.read(GRAPH), full), "undo of delete differs for " + victim);
        }
    }

    @Test
    void orphanVariableTriplesAfterADeleteDoNotChangeTheLoadedOntologyOrItsExport() throws Exception {
        List<SWRLRule> rules = demoRules();
        seed(rules);
        for (SWRLRule r : rules) {
            store.update(GRAPH, OwlAxiomSparqlWriter.toDeleteWhere(Set.of(r)));
        }

        OWLOntology after = store.ontology(GRAPH);
        OWLOntology expected = ontologyWith(List.of());
        assertTrue(RuleTestSupport.rulesOf(after).isEmpty());
        assertEquals(expected.getLogicalAxioms(), after.getLogicalAxioms());
        String turtle = save(after, new TurtleDocumentFormat());
        assertTrue(RuleTestSupport.rulesOf(RuleTestSupport.load(turtle)).isEmpty());
        assertFalse(imps(store.read(GRAPH)).iterator().hasNext());
    }
}
