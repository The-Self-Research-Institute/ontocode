package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.model.merge.ResolutionAction;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullOntologyHeaderTest {

    private static final String MAIN_GRAPH = "urn:graph:main";
    private static final String DRAFT_GRAPH = "urn:graph:draft";
    private static final String EX = "http://ex.org/";

    private static final String DECLARATIONS = "<owl:ObjectProperty rdf:about=\"" + EX + "hasTopping\"/>"
            + "<owl:ObjectProperty rdf:about=\"" + EX + "hasParent\"/>"
            + "<owl:ObjectProperty rdf:about=\"" + EX + "hasGrandparent\"/>"
            + "<owl:Class rdf:about=\"" + EX + "Cheese\"/><owl:Class rdf:about=\"" + EX + "Veggie\"/>"
            + "<owl:Class rdf:about=\"" + EX + "CheesyPizza\"/>";
    private static final String CHEESE_RULE = gci("Cheese");
    private static final String VEGGIE_RULE = gci("Veggie");
    private static final String AGE_DATATYPE = "<rdfs:Datatype rdf:about=\"" + EX + "Age\"><owl:equivalentClass>"
            + "<rdfs:Datatype><owl:onDatatype rdf:resource=\"http://www.w3.org/2001/XMLSchema#integer\"/>"
            + "<owl:withRestrictions rdf:parseType=\"Collection\"><rdf:Description>"
            + "<xsd:minInclusive rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">0</xsd:minInclusive>"
            + "</rdf:Description></owl:withRestrictions></rdfs:Datatype></owl:equivalentClass></rdfs:Datatype>";
    private static final String GRANDPARENT_CHAIN = "<rdf:Description rdf:about=\"" + EX + "hasGrandparent\">"
            + "<owl:propertyChainAxiom rdf:parseType=\"Collection\"><rdf:Description rdf:about=\"" + EX + "hasParent\"/>"
            + "<rdf:Description rdf:about=\"" + EX + "hasParent\"/></owl:propertyChainAxiom></rdf:Description>";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private DraftSessionRepository sessionRepository;
    @Mock
    private MainGraphRevisionService revisionService;
    @Mock
    private DraftBaselineStore baselineStore;
    @Mock
    private ProjectImportService importService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;

    @TempDir
    Path projectDir;

    private DraftPublishMergeService service;

    private static String gci(String filler) {
        return "<owl:Restriction><owl:onProperty rdf:resource=\"" + EX + "hasTopping\"/>"
                + "<owl:someValuesFrom rdf:resource=\"" + EX + filler + "\"/>"
                + "<rdfs:subClassOf rdf:resource=\"" + EX + "CheesyPizza\"/></owl:Restriction>";
    }

    private static String document(String headerElement, String body) {
        return "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\" xmlns:owl=\"http://www.w3.org/2002/07/owl#\" "
                + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\">"
                + headerElement + "<owl:Class rdf:about=\"" + EX + "A\"/>" + body + "</rdf:RDF>";
    }

    private static String ontology(String iri, String header, String body) {
        return document("<owl:Ontology rdf:about=\"" + iri + "\">" + header + "</owl:Ontology>", body);
    }

    private static String version(String iri) {
        return "<owl:versionIRI rdf:resource=\"" + iri + "\"/>";
    }

    private static String note(String text) {
        return "<rdfs:comment>" + text + "</rdfs:comment>";
    }

    private static String imports(String iri) {
        return "<owl:imports rdf:resource=\"" + iri + "\"/>";
    }

    private static ConflictResolution choice(ResolutionAction action) {
        ConflictResolution resolution = new ConflictResolution();
        resolution.setAction(action);
        return resolution;
    }

    private static OWLOntology parse(String rdf) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(rdf),
                new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
    }

    private static List<String> ruleFillers(OWLOntology ontology) {
        return ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                .map(axiom -> axiom.getSubClass())
                .filter(OWLClassExpression::isAnonymous)
                .map(sub -> ((OWLObjectSomeValuesFrom) sub).getFiller().asOWLClass().getIRI().getShortForm())
                .sorted()
                .collect(Collectors.toList());
    }

    private static String ontologyIri(String rdf) throws Exception {
        return parse(rdf).getOntologyID().getOntologyIRI().map(IRI::toString).orElse(null);
    }

    private void writeBaseline(String baseline) throws Exception {
        Path path = projectDir.resolve("baselines/u1.owl");
        Files.createDirectories(path.getParent());
        Files.writeString(path, baseline);
    }

    private void given(String baseline, String publicRdf, String draft) throws Exception {
        writeBaseline(baseline);
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(publicRdf);
        when(datasetService.exportNamedGraph("p1", DRAFT_GRAPH, RDFFormat.RDFXML)).thenReturn(draft);
    }

    private void givenLostBaseline(String publicRdf, String draft) {
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(publicRdf);
        when(datasetService.exportNamedGraph("p1", DRAFT_GRAPH, RDFFormat.RDFXML)).thenReturn(draft);
    }

    private String appliedDraft() {
        ArgumentCaptor<String> rdf = ArgumentCaptor.forClass(String.class);
        verify(datasetService).replaceNamedGraphFromRdf(eq("p1"), eq(DRAFT_GRAPH), rdf.capture(), eq(RDFFormat.RDFXML));
        return rdf.getValue();
    }

    private String publishedMain() {
        ArgumentCaptor<String> rdf = ArgumentCaptor.forClass(String.class);
        verify(datasetService).replaceMainGraphFromRdf(eq("p1"), rdf.capture(), eq(RDFFormat.RDFXML));
        return rdf.getValue();
    }

    private String storedBaseline() {
        ArgumentCaptor<String> rdf = ArgumentCaptor.forClass(String.class);
        verify(baselineStore).save(eq("p1"), eq("u1"), rdf.capture());
        return rdf.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> analysis, String list, String key) {
        return ((List<Map<String, Object>>) analysis.get(list)).stream()
                .filter(r -> key.equals(r.get("entityIri")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + key + " in " + analysis.get(list)));
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        OntologyMergeService mergeService = new OntologyMergeService(datasetService, importService, storageManager,
                metadataService, historyService);
        service = new DraftPublishMergeService(datasetService, storageManager, mergeService, sessionRepository,
                revisionService);
        ReflectionTestUtils.setField(service, "baselineStore", baselineStore);
        when(storageManager.projectDir("p1")).thenReturn(projectDir);
        when(datasetService.getGraphUri("p1")).thenReturn(MAIN_GRAPH);
        when(datasetService.getDraftGraphUri("p1", "u1")).thenReturn(DRAFT_GRAPH);
        when(baselineStore.load("p1", "u1")).thenReturn(Optional.empty());
        DraftSession session = new DraftSession("p1", "u1", 1, 1);
        session.setBaselineSnapshotPath("baselines/u1.owl");
        when(sessionRepository.findByProjectIdAndUserId("p1", "u1")).thenReturn(Optional.of(session));
    }

    @Test
    void aPublicOntologyIriChangeIsPulledIntoTheDraft() throws Exception {
        String old = ontology(EX + "pizza", "", "");
        given(old, ontology(EX + "pizza-v2", "", ""), old);

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(true, analysis.get("hasChanges"));
        assertEquals(false, analysis.get("hasConflicts"));
        Map<String, Object> header = row(analysis, "safeChanges", OntologyHeaderMerge.ONTOLOGY_IRI);
        assertEquals("Ontology IRI", header.get("entityLabel"));
        assertEquals(EX + "pizza-v2", header.get("publicValue"));
        assertEquals("Ontology IRI: " + EX + "pizza → " + EX + "pizza-v2", header.get("publicAxioms"));

        Map<String, Object> result = service.applyPull("p1", "u1", Map.of());
        assertEquals(true, result.get("success"));
        assertEquals(1, result.get("mergedCount"));
        String merged = appliedDraft();
        assertEquals(EX + "pizza-v2", ontologyIri(merged));
        assertFalse(merged.contains("merge-scratch"));
    }

    @Test
    void pullingAnEntityChangeKeepsTheRealOntologyIri() throws Exception {
        String old = ontology(EX + "pizza", "", "");
        given(old, ontology(EX + "pizza", "", "<owl:Class rdf:about=\"" + EX + "B\"/>"), old);

        service.applyPull("p1", "u1", Map.of());

        String merged = appliedDraft();
        assertTrue(merged.contains(EX + "B"));
        assertEquals(EX + "pizza", ontologyIri(merged));
        assertFalse(merged.contains("merge-scratch"));
    }

    @Test
    void versionIriAnnotationsAndImportsComeAcrossAndTheDraftsOwnAdditionsStay() throws Exception {
        String importA = "file:///ontocode-test-missing/a.owl";
        String importB = "file:///ontocode-test-missing/b.owl";
        String importC = "file:///ontocode-test-missing/c.owl";
        String baseline = ontology(EX + "o", version(EX + "o/1") + note("old note") + imports(importA), "");
        String publicRdf = ontology(EX + "o", version(EX + "o/2") + note("public note") + imports(importB), "");
        String draft = ontology(EX + "o", version(EX + "o/1") + note("old note") + note("my note")
                + imports(importA) + imports(importC), "");
        given(baseline, publicRdf, draft);

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(true, analysis.get("hasChanges"));
        assertEquals(EX + "o/2", row(analysis, "safeChanges", OntologyHeaderMerge.VERSION_IRI).get("publicValue"));
        Map<String, Object> annotations = row(analysis, "safeChanges", OntologyHeaderMerge.ANNOTATIONS);
        assertEquals(1, annotations.get("added"));
        assertEquals(1, annotations.get("removed"));
        assertTrue(((String) annotations.get("publicAxioms")).contains("Added: Comment: \"public note\""));
        assertEquals(1, row(analysis, "safeChanges", OntologyHeaderMerge.IMPORTS).get("added"));

        service.applyPull("p1", "u1", Map.of());

        OWLOntology merged = parse(appliedDraft());
        assertEquals(EX + "o", merged.getOntologyID().getOntologyIRI().get().toString());
        assertEquals(EX + "o/2", merged.getOntologyID().getVersionIRI().get().toString());
        List<String> notes = merged.getAnnotations().stream()
                .map(a -> a.getValue().asLiteral().get().getLiteral()).sorted().toList();
        assertEquals(List.of("my note", "public note"), notes);
        List<String> imported = merged.getImportsDeclarations().stream()
                .map(d -> d.getIRI().toString()).sorted().toList();
        assertEquals(List.of(importB, importC), imported);
    }

    @Test
    void whenBothSidesChangedTheIriTheUserMustChooseBeforeAnythingIsPulled() throws Exception {
        given(ontology(EX + "o", "", ""), ontology(EX + "o-public", "", ""), ontology(EX + "o-mine", "", ""));

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(true, analysis.get("hasConflicts"));
        Map<String, Object> conflict = row(analysis, "conflicts", OntologyHeaderMerge.ONTOLOGY_IRI);
        assertEquals(true, conflict.get("conflict"));
        assertEquals(EX + "o-mine", conflict.get("yourValue"));
        assertEquals(EX + "o-public", conflict.get("publicValue"));

        Map<String, Object> refused = service.applyPull("p1", "u1", Map.of());
        assertEquals(false, refused.get("success"));
        assertEquals(true, refused.get("needsChoice"));
        verify(datasetService, never()).replaceNamedGraphFromRdf(anyString(), anyString(), anyString(), any());
        verify(baselineStore, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    void keepingTheDraftsIriKeepsIt() throws Exception {
        given(ontology(EX + "o", "", ""), ontology(EX + "o-public", "", ""), ontology(EX + "o-mine", "", ""));

        Map<String, Object> result = service.applyPull("p1", "u1",
                Map.of(OntologyHeaderMerge.ONTOLOGY_IRI, choice(ResolutionAction.KEEP_TARGET)));

        assertEquals(true, result.get("success"));
        assertEquals(EX + "o-mine", ontologyIri(appliedDraft()));
    }

    @Test
    void takingPublicsIriTakesIt() throws Exception {
        given(ontology(EX + "o", "", ""), ontology(EX + "o-public", "", ""), ontology(EX + "o-mine", "", ""));

        service.applyPull("p1", "u1", Map.of(OntologyHeaderMerge.ONTOLOGY_IRI, choice(ResolutionAction.KEEP_SOURCE)));

        assertEquals(EX + "o-public", ontologyIri(appliedDraft()));
    }

    @Test
    void rulesDatatypeDefinitionsAndPropertyChainsAddedInPublicArePulled() throws Exception {
        String base = ontology(EX + "o", "", DECLARATIONS);
        given(base, ontology(EX + "o", "", DECLARATIONS + CHEESE_RULE + AGE_DATATYPE + GRANDPARENT_CHAIN), base);

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(true, analysis.get("hasChanges"));
        Map<String, Object> other = row(analysis, "safeChanges", OntologyHeaderMerge.OTHER_AXIOMS);
        assertEquals("other_axioms", other.get("kind"));
        assertEquals(3, other.get("added"));
        assertEquals(0, other.get("removed"));
        assertTrue(((String) other.get("publicAxioms")).contains("Rule: has some hasTopping Cheese → CheesyPizza"));

        service.applyPull("p1", "u1", Map.of());

        OWLOntology merged = parse(appliedDraft());
        assertEquals(List.of("Cheese"), ruleFillers(merged));
        assertEquals(1, merged.getAxiomCount(AxiomType.DATATYPE_DEFINITION));
        assertEquals(1, merged.getAxiomCount(AxiomType.SUB_PROPERTY_CHAIN_OF));
    }

    @Test
    void rulesRemovedInPublicAreRemovedAndTheDraftsOwnRulesStay() throws Exception {
        String base = ontology(EX + "o", "", DECLARATIONS + CHEESE_RULE + AGE_DATATYPE + GRANDPARENT_CHAIN);
        given(base, ontology(EX + "o", "", DECLARATIONS),
                ontology(EX + "o", "", DECLARATIONS + CHEESE_RULE + AGE_DATATYPE + GRANDPARENT_CHAIN + VEGGIE_RULE));

        Map<String, Object> other = row(service.analyzePull("p1", "u1"), "safeChanges", OntologyHeaderMerge.OTHER_AXIOMS);
        assertEquals(0, other.get("added"));
        assertEquals(3, other.get("removed"));

        service.applyPull("p1", "u1", Map.of());

        OWLOntology merged = parse(appliedDraft());
        assertEquals(List.of("Veggie"), ruleFillers(merged));
        assertEquals(0, merged.getAxiomCount(AxiomType.DATATYPE_DEFINITION));
        assertEquals(0, merged.getAxiomCount(AxiomType.SUB_PROPERTY_CHAIN_OF));
    }

    @Test
    void nothingChangedInPublicStillSaysUpToDate() throws Exception {
        String base = ontology(EX + "o", note("n"), DECLARATIONS + CHEESE_RULE);
        given(base, base, ontology(EX + "o-mine", note("n") + note("mine"), DECLARATIONS + CHEESE_RULE + VEGGIE_RULE));

        Map<String, Object> analysis = service.analyzePull("p1", "u1");

        assertEquals(false, analysis.get("hasChanges"));
        assertEquals(List.of(), analysis.get("safeChanges"));
        assertEquals(List.of(), analysis.get("conflicts"));
    }

    @Test
    void anUnnamedOntologyIsSavedWithoutAnyMadeUpIri() throws Exception {
        String base = document("", "");
        given(base, document("", "<owl:Class rdf:about=\"" + EX + "B\"/>"), base);

        service.applyPull("p1", "u1", Map.of());

        String merged = appliedDraft();
        assertTrue(merged.contains(EX + "B"));
        assertFalse(merged.contains("merge-scratch"));
        assertFalse(merged.contains("owl#Ontology") || merged.contains("owl:Ontology") || merged.contains("<Ontology"));
        assertTrue(parse(merged).isAnonymous());
    }

    @Test
    void anUnnamedOntologyKeepsItsAnnotations() throws Exception {
        String base = document("<owl:Ontology>" + note("about this") + "</owl:Ontology>", "");
        given(base, document("<owl:Ontology>" + note("about this") + "</owl:Ontology>",
                "<owl:Class rdf:about=\"" + EX + "B\"/>"), base);

        service.applyPull("p1", "u1", Map.of());

        String merged = appliedDraft();
        assertFalse(merged.contains("merge-scratch"));
        OWLOntology parsed = parse(merged);
        assertTrue(parsed.isAnonymous());
        assertEquals(1, parsed.getAnnotations().size());
    }

    @Test
    void aScratchIriLeftByAnEarlierPullIsReplacedByTheRealOne() throws Exception {
        String real = ontology(EX + "o", "", "");
        given(real, ontology(EX + "o", "", "<owl:Class rdf:about=\"" + EX + "B\"/>"),
                ontology("http://ontocode.org/merge-scratch/0000", "", ""));

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(false, analysis.get("hasConflicts"));

        service.applyPull("p1", "u1", Map.of());

        assertEquals(EX + "o", ontologyIri(appliedDraft()));
    }

    @Test
    void aLostBaselineListsADifferentIriAndNeedsAChoice() throws Exception {
        givenLostBaseline(ontology(EX + "o-public", note("shared") + note("from public"), ""),
                ontology(EX + "o-mine", note("shared"), ""));

        Map<String, Object> analysis = service.analyzePull("p1", "u1");
        assertEquals(true, analysis.get("directCompare"));
        Map<String, Object> iri = row(analysis, "conflicts", OntologyHeaderMerge.ONTOLOGY_IRI);
        assertEquals(EX + "o-public", iri.get("publicValue"));
        assertEquals(EX + "o-mine", iri.get("yourValue"));
        Map<String, Object> notes = row(analysis, "conflicts", OntologyHeaderMerge.ANNOTATIONS);
        assertEquals("Comment: \"from public\"", notes.get("publicAxioms"));

        Map<String, Object> refused = service.applyPull("p1", "u1", Map.of());
        assertEquals(true, refused.get("needsChoice"));
        verify(datasetService, never()).replaceNamedGraphFromRdf(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aLostBaselineTakesPublicsIriAndKeepsBothSidesNotesWhenAsked() throws Exception {
        givenLostBaseline(ontology(EX + "o-public", note("shared") + note("from public"), ""),
                ontology(EX + "o-mine", note("shared") + note("mine"), ""));

        Map<String, Object> result = service.applyPull("p1", "u1", Map.of(
                OntologyHeaderMerge.ONTOLOGY_IRI, choice(ResolutionAction.KEEP_SOURCE),
                OntologyHeaderMerge.ANNOTATIONS, choice(ResolutionAction.MERGE)));

        assertEquals(true, result.get("success"));
        OWLOntology merged = parse(appliedDraft());
        assertEquals(EX + "o-public", merged.getOntologyID().getOntologyIRI().get().toString());
        assertEquals(3, merged.getAnnotations().size());
    }

    @Test
    void aLostBaselineKeepsTheDraftsIriAndLeavesDeclinedPublicNotesOutOfTheNewBaseline() throws Exception {
        givenLostBaseline(ontology(EX + "o-public", note("from public"), ""), ontology(EX + "o-mine", "", ""));

        service.applyPull("p1", "u1", Map.of(OntologyHeaderMerge.ONTOLOGY_IRI, choice(ResolutionAction.KEEP_TARGET)));

        OWLOntology merged = parse(appliedDraft());
        assertEquals(EX + "o-mine", merged.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(merged.getAnnotations().isEmpty());
        OWLOntology baseline = parse(storedBaseline());
        assertEquals(EX + "o-public", baseline.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(baseline.getAnnotations().isEmpty());
    }

    @Test
    void publishingADraftIriChangeReachesPublic() throws Exception {
        String base = ontology(EX + "o", "", "");
        given(base, ontology(EX + "o", "", "<owl:Class rdf:about=\"" + EX + "B\"/>"), ontology(EX + "o-new", "", ""));

        service.publishWithThreeWayMerge("p1", "u1", null, Map.of());

        String published = publishedMain();
        assertEquals(EX + "o-new", ontologyIri(published));
        assertTrue(published.contains(EX + "B"));
        assertFalse(published.contains("merge-scratch"));
    }

    @Test
    void publishingAnOlderDraftDoesNotUndoANewerPublicIriChange() throws Exception {
        String base = ontology(EX + "o", "", CHEESE_RULE);
        given(base, ontology(EX + "o-v2", "", CHEESE_RULE),
                ontology(EX + "o", "", CHEESE_RULE + VEGGIE_RULE + "<owl:Class rdf:about=\"" + EX + "C\"/>"));

        service.publishWithThreeWayMerge("p1", "u1", null, Map.of());

        String published = publishedMain();
        assertEquals(EX + "o-v2", ontologyIri(published));
        assertTrue(published.contains(EX + "C"));
        assertEquals(List.of("Cheese", "Veggie"), ruleFillers(parse(published)));
        assertFalse(published.contains("merge-scratch"));
    }

    @Test
    void publishingWhenBothChangedTheIriKeepsPublicsUnlessTheDraftIsChosen() throws Exception {
        given(ontology(EX + "o", "", ""), ontology(EX + "o-public", "", ""), ontology(EX + "o-mine", "", ""));

        service.publishWithThreeWayMerge("p1", "u1", null, Map.of());

        assertEquals(EX + "o-public", ontologyIri(publishedMain()));
    }

    @Test
    void publishingWithTheDraftsIriChosenUsesIt() throws Exception {
        given(ontology(EX + "o", "", ""), ontology(EX + "o-public", "", ""), ontology(EX + "o-mine", "", ""));

        service.publishWithThreeWayMerge("p1", "u1", null,
                Map.of(OntologyHeaderMerge.ONTOLOGY_IRI, choice(ResolutionAction.KEEP_SOURCE)));

        assertEquals(EX + "o-mine", ontologyIri(publishedMain()));
    }
}
