package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.SparqlDatasetService;

import java.io.StringReader;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaRoundTripTest {

    protected static final String G = "urn:test:graph";
    private static final String P = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n";
    private static final String BASE = """
            @prefix : <http://ex/> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            :A a owl:Class ; rdfs:label "A"@en , "Ah"@fr ; rdfs:comment "about A" ; rdfs:subClassOf :B .
            :B a owl:Class ; rdfs:label "B" .
            :C a owl:Class ; rdfs:label "C" .
            :D a owl:Class ; owl:disjointWith :C .
            :C owl:disjointWith :D .
            :p a owl:ObjectProperty ; rdfs:domain :A ; rdfs:range :B .
            :q a owl:ObjectProperty ; owl:inverseOf :p .
            :p owl:inverseOf :q .
            :d a owl:DatatypeProperty .
            :i1 a owl:NamedIndividual , :A ; :d "5"^^xsd:integer ; :p :i2 .
            :i2 a owl:NamedIndividual , :B ; owl:sameAs :i3 .
            :i3 owl:sameAs :i2 .
            """;

    protected final org.eclipse.rdf4j.model.ValueFactory vf = SimpleValueFactory.getInstance();
    protected Repository repo;
    private SparqlDatasetService injector;
    private OntologyMutationService mutator;

    @BeforeEach
    void setUp() {
        repo = createRepository();
        injector = Mockito.mock(SparqlDatasetService.class, Mockito.CALLS_REAL_METHODS);
        mutator = Mockito.mock(OntologyMutationService.class, Mockito.CALLS_REAL_METHODS);
    }

    @AfterEach
    void tearDown() {
        destroyRepository();
    }

    protected Repository createRepository() {
        Repository created = new SailRepository(new MemoryStore());
        created.init();
        return created;
    }

    protected void destroyRepository() {
        repo.shutDown();
    }

    private static MutationOp op(String type, String iri, String property, String value, String target) {
        return new MutationOp(type, iri, null, null, property, value, target, null, null, null, null, null, null, null, null);
    }

    private static MutationOp full(String type, String iri, String property, String value, String target,
                                   String restrictionType, Integer cardinality, String axiomType, String oldValue,
                                   String language, String datatype) {
        return new MutationOp(type, iri, null, null, property, value, target, null, restrictionType, cardinality,
                axiomType, oldValue, language, datatype, null);
    }

    private static String ex(String local) {
        return "http://ex/" + local;
    }

    private String updateFor(MutationOp... ops) {
        return List.of(ops).stream()
                .map(o -> (String) ReflectionTestUtils.invokeMethod(mutator, "toUpdate", "p", o))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n;\n"));
    }

    protected void load(String ttl) throws Exception {
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(new StringReader(ttl), "", RDFFormat.TURTLE, vf.createIRI(G));
        }
    }

    private void apply(String update) {
        String body = update.replaceAll("(?m)^\\s*PREFIX\\s+(rdf|rdfs|owl|xsd):.*$", "");
        String injected = ReflectionTestUtils.invokeMethod(injector, "injectGraphContext", P + body, G);
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(injected).execute();
        }
    }

    private Model dump() {
        try (RepositoryConnection c = repo.getConnection()) {
            Model model = new LinkedHashModel();
            c.getStatements(null, null, null, false, vf.createIRI(G)).forEach(s -> model.add(s.getSubject(), s.getPredicate(), s.getObject()));
            return model;
        }
    }

    private boolean ask(String query) {
        try (RepositoryConnection c = repo.getConnection()) {
            return c.prepareBooleanQuery(query).evaluate();
        }
    }

    private void assertHolds(ExactDelta delta, boolean undo) {
        for (String query : DeltaSparql.holds(delta, undo, G)) {
            assertTrue(ask(query), query);
        }
    }

    private ExactDelta roundTrip(String ttl, String update, List<MutationOp> ops) throws Exception {
        load(ttl);
        Model before = dump();
        DeltaCapture capture = DeltaCapture.begin(repo, G, ExactSeeds.seeds(ops), ExactSeeds.incomingSeeds(ops)).orElseThrow();
        apply(update);
        Model changed = dump();
        assertFalse(Models.isomorphic(before, changed), "the operation changed nothing");
        ExactDelta delta = capture.finish().orElseThrow();
        assertHolds(delta, true);

        String reverse = DeltaSparql.reverse(delta);
        apply(reverse);
        assertTrue(Models.isomorphic(before, dump()), "undo did not restore the graph\nDELTA del=" + ExactDelta.write(delta.deleted())
                + "\nins=" + ExactDelta.write(delta.inserted()) + "\nREVERSE=" + reverse + "\nNOW=" + ExactDelta.write(dump()));
        assertHolds(delta, false);

        apply(DeltaSparql.forward(delta));
        assertTrue(Models.isomorphic(changed, dump()), "redo did not reproduce the change");
        assertHolds(delta, true);

        apply(DeltaSparql.reverse(delta));
        assertTrue(Models.isomorphic(before, dump()), "second undo did not restore the graph");
        return delta;
    }

    private ExactDelta check(String ttl, MutationOp... ops) throws Exception {
        return roundTrip(ttl, updateFor(ops), List.of(ops));
    }

    @Test
    void labelReplacementRestoresEveryLanguage() throws Exception {
        MutationOp op = new MutationOp("updateClassLabel", ex("A"), "Alpha", null, null, null, null, null, null, null, null,
                null, null, null, null);
        ExactDelta delta = check(BASE, op);
        assertEquals(2, delta.deleted().size());
    }

    @Test
    void annotationAddKeepsLanguage() throws Exception {
        check(BASE, full("addAnnotation", ex("A"), "http://www.w3.org/2000/01/rdf-schema#label", "Alfa", null, null, null,
                null, null, "it", null));
    }

    @Test
    void annotationDeleteOfOneLanguageLeavesTheOther() throws Exception {
        ExactDelta delta = check(BASE, full("deleteAnnotation", ex("A"), "http://www.w3.org/2000/01/rdf-schema#label", "A",
                null, null, null, null, null, "en", null));
        assertEquals(1, delta.deleted().size());
    }

    @Test
    void annotationUpdate() throws Exception {
        check(BASE, full("updateAnnotation", ex("A"), "http://www.w3.org/2000/01/rdf-schema#comment", "changed", null, null,
                null, null, "about A", null, null));
    }

    @Test
    void subClassOfAddDeleteUpdate() throws Exception {
        check(BASE, op("addSubClassOf", ex("A"), null, null, ex("C")));
        tearDown();
        setUp();
        check(BASE, op("deleteSubClassOf", ex("A"), null, null, ex("B")));
        tearDown();
        setUp();
        check(BASE, op("updateSubClassOf", ex("A"), null, ex("B"), ex("C")));
    }

    @Test
    void equivalentClassAddAndDelete() throws Exception {
        check(BASE, op("addEquivalentClass", ex("A"), null, null, ex("C")));
        tearDown();
        setUp();
        String ttl = BASE + ":A owl:equivalentClass :C .\n";
        check(ttl, op("deleteEquivalentClass", ex("A"), null, null, ex("C")));
    }

    @Test
    void deletingDisjointnessRemovesBothDirectionsAndUndoRestoresBoth() throws Exception {
        ExactDelta delta = check(BASE, op("deleteDisjointWith", ex("D"), null, null, ex("C")));
        assertEquals(2, delta.deleted().size());
    }

    @Test
    void addingDisjointnessAddsOneDirection() throws Exception {
        ExactDelta delta = check(BASE, op("addDisjointWith", ex("A"), null, null, ex("C")));
        assertEquals(1, delta.inserted().size());
    }

    @Test
    void inversePropertyPair() throws Exception {
        ExactDelta delta = check(BASE, op("deleteInverseProperty", ex("p"), null, null, ex("q")));
        assertEquals(2, delta.deleted().size());
        tearDown();
        setUp();
        check(BASE, op("addInverseProperty", ex("d"), null, null, ex("q")));
    }

    @Test
    void domainAndRange() throws Exception {
        check(BASE, op("addPropertyDomain", ex("q"), null, null, ex("C")));
        tearDown();
        setUp();
        check(BASE, op("deletePropertyRange", ex("p"), null, null, ex("B")));
        tearDown();
        setUp();
        check(BASE, op("updatePropertyDomain", ex("p"), null, ex("A"), ex("C")));
    }

    @Test
    void individualAssertions() throws Exception {
        check(BASE, op("addObjectPropertyAssertion", ex("i2"), ex("p"), null, ex("i1")));
        tearDown();
        setUp();
        check(BASE, op("deleteObjectPropertyAssertion", ex("i1"), ex("p"), null, ex("i2")));
        tearDown();
        setUp();
        check(BASE, full("addDataPropertyAssertion", ex("i2"), ex("d"), "7", null, null, null, null, null, null,
                "http://www.w3.org/2001/XMLSchema#integer"));
    }

    @Test
    void sameAndDifferentIndividuals() throws Exception {
        ExactDelta delta = check(BASE, op("deleteSameIndividual", ex("i2"), null, null, ex("i3")));
        assertEquals(2, delta.deleted().size());
        tearDown();
        setUp();
        ExactDelta added = check(BASE, op("addDifferentIndividual", ex("i1"), null, null, ex("i2")));
        assertEquals(2, added.inserted().size());
    }

    @Test
    void classAssertion() throws Exception {
        MutationOp add = new MutationOp("addClassAssertion", ex("i2"), null, null, null, null, null, ex("C"), null, null,
                null, null, null, null, null);
        check(BASE, add);
        tearDown();
        setUp();
        MutationOp remove = new MutationOp("removeClassAssertion", ex("i1"), null, null, null, null, null, ex("A"), null,
                null, null, null, null, null, null);
        check(BASE, remove);
    }

    @Test
    void negativeAssertions() throws Exception {
        check(BASE, op("addNegativeObjectPropertyAssertion", ex("i1"), ex("p"), null, ex("i1")));
        tearDown();
        setUp();
        String ttl = BASE + "<http://ontocode.org/axiom/negativePropertyAssertion/x> a owl:NegativePropertyAssertion ;"
                + " owl:sourceIndividual :i1 ; owl:assertionProperty :p ; owl:targetIndividual :i2 .\n";
        ExactDelta delta = roundTrip(ttl, "DELETE WHERE { <http://ontocode.org/axiom/negativePropertyAssertion/x> ?p ?o }",
                List.of(op("x", ex("i1"), null, null, null), op("x", "http://ontocode.org/axiom/negativePropertyAssertion/x", null, null, null)));
        assertEquals(4, delta.deleted().size());
    }

    @Test
    void objectRestrictionAddCreatesAFreshStructureAndUndoRemovesExactlyIt() throws Exception {
        MutationOp add = full("addObjectRestriction", ex("C"), ex("p"), null, ex("B"), "some", null, null, null, null, null);
        ExactDelta delta = check(BASE, add);
        assertTrue(delta.inserted().size() >= 4);
        assertTrue(delta.deleted().isEmpty());
    }

    @Test
    void cardinalityRestrictionKeepsTypedNumber() throws Exception {
        MutationOp add = full("addObjectRestriction", ex("C"), ex("p"), null, ex("B"), "min", 2, null, null, null, null);
        ExactDelta delta = check(BASE, add);
        assertTrue(ExactDelta.write(delta.inserted()).contains("nonNegativeInteger"));
    }

    @Test
    void restrictionDeleteWritesBackTheWholeStructure() throws Exception {
        String ttl = BASE + """
                :C rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :B ] .
                """;
        MutationOp delete = full("deleteObjectRestriction", ex("C"), ex("p"), null, ex("B"), "some", null, null, null, null,
                null);
        ExactDelta delta = check(ttl, delete);
        assertEquals(4, delta.deleted().size());
        assertTrue(delta.inserted().isEmpty());
    }

    @Test
    void unionAndIntersectionExpressions() throws Exception {
        MutationOp union = full("addUnion", ex("C"), null, ex("A") + "," + ex("B"), null, null, null, "EquivalentTo", null, null,
                null);
        ExactDelta added = check(BASE, union);
        assertTrue(added.inserted().size() >= 5);
        tearDown();
        setUp();
        MutationOp intersection = full("addIntersection", ex("D"), null, ex("A") + "," + ex("B"), null, null, null,
                "SubClassOf", null, null, null);
        check(BASE, intersection);
    }

    @Test
    void propertyChainAndHasKey() throws Exception {
        check(BASE, op("addPropertyChain", ex("p"), null, ex("p") + " o " + ex("q"), null));
        tearDown();
        setUp();
        check(BASE, op("addHasKey", ex("A"), null, ex("d"), null));
    }

    @Test
    void disjointUnion() throws Exception {
        check(BASE, op("addDisjointUnion", ex("A"), null, ex("B") + "," + ex("C"), null));
    }

    @Test
    void complementAndOneOf() throws Exception {
        check(BASE, full("addComplement", ex("C"), null, null, ex("B"), null, null, "EquivalentTo", null, null, null));
        tearDown();
        setUp();
        check(BASE, full("addOneOf", ex("C"), null, ex("i1") + "," + ex("i2"), null, null, null, "EquivalentTo", null, null,
                null));
    }

    @Test
    void generalClassAxioms() throws Exception {
        check(BASE, op("addGCAIntersection", ex("A"), null, ex("B") + "," + ex("C"), null));
        tearDown();
        setUp();
        check(BASE, op("addGCAUnion", ex("A"), null, ex("B") + "," + ex("C"), null));
    }

    @Test
    void datatypeDefinition() throws Exception {
        String ttl = BASE + ":MyType a rdfs:Datatype .\n";
        check(ttl, op("addDatatypeDefinition", ex("MyType"), null, "xsd:integer[>= 1, <= 5]", null));
    }

    @Test
    void axiomKinds() throws Exception {
        MutationOp same = new MutationOp("addAxiom", ex("i1"), null, null, null, "SameIndividual", ex("i2"), ex("i1"), null, null,
                null, null, null, null, null);
        check(BASE, same);
    }

    @Test
    void changesInsideBlankNodesRestoreTheOriginalStructure() throws Exception {
        String ttl = BASE + """
                :C rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :B ] .
                """;
        String update = """
                DELETE { ?r owl:someValuesFrom <http://ex/B> } INSERT { ?r owl:someValuesFrom <http://ex/A> }
                WHERE { <http://ex/C> rdfs:subClassOf ?r . ?r owl:someValuesFrom <http://ex/B> }
                """;
        ExactDelta delta = roundTrip(ttl, update, List.of(op("x", ex("C"), null, null, null)));
        assertTrue(delta.deleted().size() >= 4);
    }

    @Test
    void aTextValueContainingTheWordUsingSurvivesUndoAndRedo() throws Exception {
        String update = """
                INSERT { <http://ex/C> rdfs:subClassOf _:r . _:r a owl:Restriction ; owl:onProperty <http://ex/p> ;
                owl:someValuesFrom <http://ex/B> ; rdfs:comment "measured using a probe" . } WHERE { }
                """;
        ExactDelta delta = roundTrip(BASE, update, List.of(op("x", ex("C"), null, null, null)));
        assertTrue(ExactDelta.write(delta.inserted()).contains("using a probe"));
    }

    @Test
    void anOperationThatChangesNothingProducesNoDelta() throws Exception {
        load(BASE);
        DeltaCapture capture = DeltaCapture.begin(repo, G, List.of(ex("A"))).orElseThrow();
        apply(updateFor(op("addSubClassOf", ex("A"), null, null, ex("B"))));
        assertTrue(capture.finish().isEmpty());
    }

    @Test
    void detectsWhenSomeoneElseChangedTheSameTriple() throws Exception {
        load(BASE);
        MutationOp add = op("addSubClassOf", ex("A"), null, null, ex("C"));
        DeltaCapture capture = DeltaCapture.begin(repo, G, ExactSeeds.seeds(List.of(add))).orElseThrow();
        apply(updateFor(add));
        ExactDelta delta = capture.finish().orElseThrow();
        apply(updateFor(op("deleteSubClassOf", ex("A"), null, null, ex("C"))));
        assertFalse(DeltaSparql.holds(delta, true, G).stream().allMatch(this::ask));
    }

    @Test
    void stringsWithBracesAndQuotesSurviveTheRoundTrip() throws Exception {
        String update = "INSERT DATA { <http://ex/A> <http://www.w3.org/2000/01/rdf-schema#comment> \"{x}; \\\"q\\\" line\\nnext\" . }";
        ExactDelta delta = roundTrip(BASE, update, List.of(op("x", ex("A"), null, null, null)));
        assertEquals(1, delta.inserted().size());
    }

    @Test
    void storedFormReadsBackToTheSameDelta() throws Exception {
        MutationOp add = full("addObjectRestriction", ex("C"), ex("p"), null, ex("B"), "some", null, null, null, null, null);
        ExactDelta delta = check(BASE, add);
        ExactDelta reread = ExactDelta.of(ExactDelta.write(delta.deleted()), ExactDelta.write(delta.inserted()));
        assertTrue(Models.isomorphic(delta.inserted(), reread.inserted()));
        IRI c = vf.createIRI(ex("C"));
        assertTrue(reread.inserted().contains(c, null, null));
    }

    private ExactDelta wholeGraph(String ttl, MutationOp... ops) throws Exception {
        load(ttl);
        Model before = dump();
        DeltaCapture capture = DeltaCapture.beginWholeGraph(repo, G).orElseThrow();
        apply(updateFor(ops));
        Model changed = dump();
        assertFalse(Models.isomorphic(before, changed), "the operation changed nothing");
        ExactDelta delta = capture.finish().orElseThrow();
        assertHolds(delta, true);
        apply(DeltaSparql.reverse(delta));
        assertTrue(Models.isomorphic(before, dump()), "undo did not restore the graph\nDELTA del=" + ExactDelta.write(delta.deleted())
                + "\nins=" + ExactDelta.write(delta.inserted()) + "\nNOW=" + ExactDelta.write(dump()));
        assertHolds(delta, false);
        apply(DeltaSparql.forward(delta));
        assertTrue(Models.isomorphic(changed, dump()), "redo did not reproduce the change");
        apply(DeltaSparql.reverse(delta));
        assertTrue(Models.isomorphic(before, dump()), "second undo did not restore the graph");
        return delta;
    }

    private static final String WITH_REFERENCES = BASE + """
            :E a owl:Class ; rdfs:subClassOf :A .
            :F a owl:Class ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:someValuesFrom :A ] .
            :G a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:unionOf ( :A :C ) ] .
            [ a owl:AllDisjointClasses ; owl:members ( :A :C :D ) ] .
            :i4 a owl:NamedIndividual , :A .
            """;

    @Test
    void deletingAClassRestoresChildrenRestrictionsAndListAxiomsThatReferToIt() throws Exception {
        MutationOp delete = op("deleteClass", ex("A"), null, null, null);
        ExactDelta delta = wholeGraph(WITH_REFERENCES, delete);
        assertTrue(delta.deleted().size() > 10);
        assertTrue(delta.inserted().isEmpty());
    }

    @Test
    void aBlankNodeWithNoTriplesElsewhereInTheGraphDoesNotStopTheCapture() throws Exception {
        MutationOp delete = op("deleteClass", ex("A"), null, null, null);
        ExactDelta delta = wholeGraph(WITH_REFERENCES + ":Z a owl:Class ; rdfs:seeAlso [] .", delete);
        assertTrue(delta.deleted().size() > 10);
    }

    @Test
    void deletingAnObjectPropertyRestoresItsRestrictionsAndInverse() throws Exception {
        wholeGraph(WITH_REFERENCES, op("deleteObjectProperty", ex("p"), null, null, null));
    }

    @Test
    void deletingAnIndividualAndADataPropertyAndADatatype() throws Exception {
        wholeGraph(WITH_REFERENCES, op("deleteIndividual", ex("i1"), null, null, null));
        tearDown();
        setUp();
        wholeGraph(WITH_REFERENCES, op("deleteDataProperty", ex("d"), null, null, null));
    }

    @Test
    void deletingTwoEntitiesInOneRequestIsOneDelta() throws Exception {
        ExactDelta delta = wholeGraph(WITH_REFERENCES, op("deleteClass", ex("C"), null, null, null),
                op("deleteIndividual", ex("i4"), null, null, null));
        assertTrue(delta.deleted().size() > 8);
    }

    @Test
    void creatingEntitiesIsReversedExactly() throws Exception {
        MutationOp create = new MutationOp("createClass", ex("New"), "New", ex("A"), null, null, null, null, null, null, null,
                null, null, null, null);
        ExactDelta delta = check(BASE, create);
        assertTrue(delta.inserted().size() >= 3);
        tearDown();
        setUp();
        MutationOp individual = new MutationOp("createIndividual", ex("i9"), "i9", null, null, null, null, ex("A"), null, null,
                null, null, null, null, null);
        check(BASE, individual);
    }
}
