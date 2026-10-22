package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.controller.DraftController;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestFullDiffTest {

    private static final String MAIN_GRAPH = "urn:graph:main";
    private static final String DRAFT_GRAPH = "urn:graph:draft";
    private static final String EX = "http://ex.org/";

    private final SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
    private final StorageManager storageManager = mock(StorageManager.class);
    private final DraftSessionRepository sessionRepository = mock(DraftSessionRepository.class);
    private final MainGraphRevisionService revisionService = mock(MainGraphRevisionService.class);
    private final DraftChangeRepository draftRepository = mock(DraftChangeRepository.class);
    private final DraftCopyService draftCopyService = mock(DraftCopyService.class);
    private final DraftChangeHistoryRecorder draftChangeHistory = mock(DraftChangeHistoryRecorder.class);
    private final DraftPullRequestRepository prRepository = mock(DraftPullRequestRepository.class);
    private final WorkspaceOwnershipService ownershipService = mock(WorkspaceOwnershipService.class);
    private final ProjectMetadataService metadataService = mock(ProjectMetadataService.class);

    @TempDir
    Path projectDir;

    private DraftPublishMergeService mergeService;
    private DraftController controller;
    private DraftPullRequest pr;

    private static String ontology(String iri, String body) {
        return "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\" xmlns:owl=\"http://www.w3.org/2002/07/owl#\">"
                + "<owl:Ontology rdf:about=\"" + iri + "\"/>"
                + "<owl:Class rdf:about=\"" + EX + "A\"><rdfs:label>Pizza</rdfs:label></owl:Class>"
                + body + "</rdf:RDF>";
    }

    private static String parentOfA(String parent) {
        return "<owl:Class rdf:about=\"" + EX + parent + "\"/>"
                + "<rdf:Description rdf:about=\"" + EX + "A\"><rdfs:subClassOf rdf:resource=\"" + EX + parent
                + "\"/></rdf:Description>";
    }

    private static String rule(String filler) {
        return "<owl:ObjectProperty rdf:about=\"" + EX + "hasTopping\"/><owl:Class rdf:about=\"" + EX + filler + "\"/>"
                + "<owl:Restriction><owl:onProperty rdf:resource=\"" + EX + "hasTopping\"/>"
                + "<owl:someValuesFrom rdf:resource=\"" + EX + filler + "\"/>"
                + "<rdfs:subClassOf rdf:resource=\"" + EX + "A\"/></owl:Restriction>";
    }

    private void given(String baseline, String publicRdf, String draft, boolean publicMoved) throws Exception {
        Path path = projectDir.resolve("baselines/author1.owl");
        Files.createDirectories(path.getParent());
        Files.writeString(path, baseline);
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML)).thenReturn(publicRdf);
        when(datasetService.exportNamedGraph("p1", DRAFT_GRAPH, RDFFormat.RDFXML)).thenReturn(draft);
        when(revisionService.getRevision("p1")).thenReturn(publicMoved ? 6L : 5L);
    }

    private static Set<String> parents(String rdf) throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(rdf));
        return ontology.getAxioms(AxiomType.SUBCLASS_OF).stream()
                .filter(axiom -> !axiom.getSubClass().isAnonymous()
                        && axiom.getSubClass().asOWLClass().getIRI().toString().equals(EX + "A"))
                .map(axiom -> axiom.getSuperClass().asOWLClass().getIRI().getShortForm())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static String ontologyIri(String rdf) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(rdf))
                .getOntologyID().getOntologyIRI().map(Object::toString).orElse(null);
    }

    private String publishedMain() {
        ArgumentCaptor<String> rdf = ArgumentCaptor.forClass(String.class);
        verify(datasetService).replaceMainGraphFromRdf(eq("p1"), rdf.capture(), eq(RDFFormat.RDFXML));
        return rdf.getValue();
    }

    private ResponseEntity<Map<String, Object>> approve(Map<String, String> resolutions) {
        return controller.approvePullRequest("p1", "pr1",
                new DraftController.ReviewPRRequest("reviewer1", "ok", resolutions));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> change(Map<String, Object> diff, String iri) {
        return ((List<Map<String, Object>>) diff.get("changes")).stream()
                .filter(row -> iri.equals(row.get("entityIri")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + iri + " in " + diff.get("changes")));
    }

    @BeforeEach
    void setUp() {
        OntologyMergeService owlMerge = new OntologyMergeService(datasetService, mock(ProjectImportService.class),
                storageManager, metadataService, mock(OntologyHistoryService.class));
        mergeService = new DraftPublishMergeService(datasetService, storageManager, owlMerge, sessionRepository,
                revisionService);
        ReflectionTestUtils.setField(mergeService, "baselineStore", mock(DraftBaselineStore.class));
        DraftTrackingService tracking = new DraftTrackingService(draftRepository, mock(OntologyMutationService.class),
                datasetService, mock(OntologyIndexService.class), metadataService, draftChangeHistory, Runnable::run,
                mock(CollaborativeEditService.class), mock(DraftPublishService.class), revisionService, mergeService,
                draftCopyService);
        controller = new DraftController(tracking, draftCopyService, metadataService, prRepository, ownershipService,
                mergeService);

        when(storageManager.projectDir("p1")).thenReturn(projectDir);
        when(datasetService.getGraphUri("p1")).thenReturn(MAIN_GRAPH);
        when(datasetService.getDraftGraphUri("p1", "author1")).thenReturn(DRAFT_GRAPH);
        when(datasetService.countMainGraphTriples("p1")).thenReturn(40L);
        DraftSession session = new DraftSession("p1", "author1", 5, 40);
        session.setBaselineSnapshotPath("baselines/author1.owl");
        when(sessionRepository.findByProjectIdAndUserId("p1", "author1")).thenReturn(Optional.of(session));
        when(draftCopyService.isReady("p1", "author1")).thenReturn(true);
        when(draftCopyService.getMainRevisionAtCopy("p1", "author1")).thenReturn(5L);
        DraftChange draft = new DraftChange("p1", "author1", "Ann", "ontologyMerge", Map.of("iri", "urn:ontocode:merge:1"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "author1"))
                .thenReturn(List.of(draft));
        when(ownershipService.canPublishToProject("reviewer1", "p1")).thenReturn(true);
        pr = new DraftPullRequest();
        pr.setId("pr1");
        pr.setProjectId("p1");
        pr.setAuthorId("author1");
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
    }

    @Test
    void twoMergesThatChangedTheSameClassDifferentlyAreAConflict() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", parentOfA("Food")), ontology(EX + "o", parentOfA("Dish")), true);

        Map<String, Object> diff = mergeService.analyzePublish("p1", "author1");

        assertEquals(true, diff.get("available"));
        assertEquals(false, diff.get("baselineLost"));
        assertEquals(true, diff.get("publicChangedSinceDraft"));
        assertEquals(1, diff.get("conflictCount"));
        Map<String, Object> a = change(diff, EX + "A");
        assertEquals(true, a.get("conflict"));
        assertEquals("changed", a.get("change"));
        assertEquals("class", a.get("kind"));
        assertEquals("Pizza", a.get("entityLabel"));
        assertEquals(List.of("Kind of: Dish"), a.get("yourAxioms"));
        assertEquals(List.of("Kind of: Food"), a.get("publicAxioms"));
        Map<String, Object> dish = change(diff, EX + "Dish");
        assertEquals(false, dish.get("conflict"));
        assertEquals("added", dish.get("change"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvingWithoutAChoiceForTheConflictIsRefusedWithTheFullComparison() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", parentOfA("Food")), ontology(EX + "o", parentOfA("Dish")), true);

        ResponseEntity<Map<String, Object>> response = approve(null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        Map<String, Object> conflicts = (Map<String, Object>) response.getBody().get("conflicts");
        assertEquals(1, conflicts.get("conflictCount"));
        assertEquals(DraftPullRequest.Status.OPEN, pr.getStatus());
        verify(datasetService, never()).replaceMainGraphFromRdf(anyString(), anyString(), any());
        verify(datasetService, never()).moveDraftToMain(anyString(), anyString());
    }

    @Test
    void keepingTheDraftsVersionPublishesItAndKeepsPublicsOtherWork() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", parentOfA("Food")), ontology(EX + "o", parentOfA("Dish")), true);

        ResponseEntity<Map<String, Object>> response = approve(Map.of(EX + "A", "keep_draft"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        String published = publishedMain();
        assertEquals(Set.of("Dish"), parents(published));
        assertTrue(published.contains(EX + "Food"));
        assertFalse(published.contains("merge-scratch"));
        verify(datasetService, never()).moveDraftToMain(anyString(), anyString());
    }

    @Test
    void takingPublicsVersionKeepsItAndStillAddsTheDraftsNewItems() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", parentOfA("Food")), ontology(EX + "o", parentOfA("Dish")), true);

        approve(Map.of(EX + "A", "take_public"));

        String published = publishedMain();
        assertEquals(Set.of("Food"), parents(published));
        assertTrue(published.contains(EX + "Dish"));
        assertEquals(EX + "o", ontologyIri(published));
    }

    @Test
    void publicChangesElsewhereAreMergedInsteadOfOverwrittenWhenNothingConflicts() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", rule("Cheese")), ontology(EX + "o", parentOfA("Dish")), true);

        ResponseEntity<Map<String, Object>> response = approve(null);

        assertEquals(200, response.getStatusCode().value());
        String published = publishedMain();
        assertEquals(Set.of("Dish"), parents(published));
        assertTrue(published.contains(EX + "Cheese"));
        verify(datasetService, never()).moveDraftToMain(anyString(), anyString());
    }

    @Test
    void aDraftOnlyChangeWithPublicUnchangedUsesTheFastPath() throws Exception {
        String base = ontology(EX + "o", "");
        given(base, base, ontology(EX + "o", parentOfA("Dish")), false);

        Map<String, Object> diff = mergeService.analyzePublish("p1", "author1");
        assertEquals(false, diff.get("publicChangedSinceDraft"));
        assertEquals(0, diff.get("conflictCount"));

        ResponseEntity<Map<String, Object>> response = approve(null);

        assertEquals(200, response.getStatusCode().value());
        verify(datasetService).moveDraftToMain("p1", "author1");
        verify(datasetService, never()).replaceMainGraphFromRdf(anyString(), anyString(), any());
    }

    @Test
    void aPublicIriChangeWithoutARevisionBumpStillStopsTheFastPath() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o-v2", ""), ontology(EX + "o", parentOfA("Dish")), false);

        ResponseEntity<Map<String, Object>> response = approve(null);

        assertEquals(200, response.getStatusCode().value());
        verify(datasetService, never()).moveDraftToMain(anyString(), anyString());
        String published = publishedMain();
        assertEquals(EX + "o-v2", ontologyIri(published));
        assertEquals(Set.of("Dish"), parents(published));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOntologyIriChangedOnBothSidesIsAConflictTheReviewerResolves() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o-public", ""), ontology(EX + "o-mine", ""), true);

        Map<String, Object> diff = mergeService.analyzePublish("p1", "author1");
        Map<String, Object> header = ((List<Map<String, Object>>) diff.get("header")).get(0);
        assertEquals("ontologyIri", header.get("field"));
        assertEquals(EX + "o", header.get("baselineValue"));
        assertEquals(EX + "o-mine", header.get("yourValue"));
        assertEquals(EX + "o-public", header.get("publicValue"));
        assertEquals(true, header.get("conflict"));
        assertEquals(1, diff.get("conflictCount"));

        assertEquals(409, approve(null).getStatusCode().value());
        assertEquals(200, approve(Map.of("header:ontologyIri", "keep_draft")).getStatusCode().value());
        assertEquals(EX + "o-mine", ontologyIri(publishedMain()));
    }

    @Test
    void takingPublicsOntologyIriKeepsIt() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o-public", ""), ontology(EX + "o-mine", ""), true);

        approve(Map.of("header:ontologyIri", "take_public"));

        String published = publishedMain();
        assertEquals(EX + "o-public", ontologyIri(published));
        assertFalse(published.contains("merge-scratch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherAxiomsTheDraftAddedAreListed() throws Exception {
        String base = ontology(EX + "o", "");
        given(base, base, ontology(EX + "o", rule("Cheese")), false);

        Map<String, Object> other = (Map<String, Object>) mergeService.analyzePublish("p1", "author1").get("otherAxioms");

        assertEquals(1, other.get("added"));
        assertEquals(0, other.get("removed"));
        assertEquals(List.of(Map.of("axiom", "Rule: has some hasTopping Cheese → A", "change", "added")),
                other.get("items"));
    }

    @Test
    void aLostBaselineComparesWithPublicAndNeedsChoicesWhenPublicMoved() throws Exception {
        when(datasetService.exportNamedGraph("p1", MAIN_GRAPH, RDFFormat.RDFXML))
                .thenReturn(ontology(EX + "o", parentOfA("Food")));
        when(datasetService.exportNamedGraph("p1", DRAFT_GRAPH, RDFFormat.RDFXML))
                .thenReturn(ontology(EX + "o", parentOfA("Dish")));
        when(revisionService.getRevision("p1")).thenReturn(6L);

        Map<String, Object> diff = mergeService.analyzePublish("p1", "author1");
        assertEquals(true, diff.get("baselineLost"));
        assertEquals(true, change(diff, EX + "A").get("conflict"));
        assertEquals(false, change(diff, EX + "Dish").get("conflict"));
        assertEquals(409, approve(null).getStatusCode().value());

        assertEquals(200, approve(Map.of(EX + "A", "keep_draft", EX + "Food", "take_public")).getStatusCode().value());
        String published = publishedMain();
        assertEquals(Set.of("Dish"), parents(published));
        assertTrue(published.contains(EX + "Food"));
        assertFalse(published.contains("merge-scratch"));
    }

    @Test
    void theFullDiffEndpointReturnsTheComparison() throws Exception {
        given(ontology(EX + "o", ""), ontology(EX + "o", parentOfA("Food")), ontology(EX + "o", parentOfA("Dish")), true);

        ResponseEntity<Map<String, Object>> response = controller.fullDiff("p1", "author1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().get("conflictCount"));
    }

    @Test
    void aDraftWithNoSessionIsNotAvailable() throws Exception {
        when(sessionRepository.findByProjectIdAndUserId("p1", "nobody")).thenReturn(Optional.empty());

        Map<String, Object> diff = mergeService.analyzePublish("p1", "nobody");

        assertEquals(false, diff.get("available"));
        assertEquals(List.of(), diff.get("changes"));
    }
}
